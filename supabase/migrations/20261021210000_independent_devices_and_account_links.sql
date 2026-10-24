-- Keep sensors and cameras as separate records and share an owner's full device set with one caregiver link.
alter table public.devices drop constraint if exists devices_device_type_check;
alter table public.devices add constraint devices_device_type_check
    check (device_type in ('phone', 'arduino', 'wearable', 'camera', 'fall_sensor', 'unknown'));

alter table public.caregiver_links alter column device_id drop not null;
alter table public.caregiver_invites alter column device_id drop not null;
create unique index if not exists caregiver_links_account_scope_unique
    on public.caregiver_links (caregiver_user_id, owner_user_id) where device_id is null;

create or replace function public.is_linked_caregiver(p_owner_user_id uuid, p_device_id uuid default null)
returns boolean
language sql
stable
security invoker
set search_path = ''
as $$
    select exists (
        select 1
        from public.caregiver_links cl
        where cl.caregiver_user_id = (select auth.uid())
          and cl.owner_user_id = p_owner_user_id
          and cl.status = 'active'
          and (cl.device_id is null or p_device_id is null or cl.device_id = p_device_id)
    );
$$;

create or replace function public.create_caregiver_account_invite()
returns table (code text, expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner uuid := auth.uid();
    v_bytes bytea := extensions.gen_random_bytes(8);
    v_alphabet text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    v_code text := '';
    v_expiry timestamptz := now() + interval '10 minutes';
    v_index integer;
begin
    if v_owner is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    delete from public.caregiver_invites ci where ci.owner_user_id = v_owner and ci.claimed_at is null;
    for v_index in 0..7 loop
        v_code := v_code || substr(v_alphabet, (get_byte(v_bytes, v_index) % 32) + 1, 1);
    end loop;
    insert into public.caregiver_invites (owner_user_id, device_id, code_hash, expires_at)
    values (v_owner, null, encode(extensions.digest(v_code, 'sha256'), 'hex'), v_expiry);
    return query select substr(v_code, 1, 4) || '-' || substr(v_code, 5, 4), v_expiry;
end;
$$;

create or replace function public.accept_caregiver_invite(p_code text)
returns table (link_id uuid, owner_user_id uuid, device_id uuid, linked_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caregiver uuid := auth.uid();
    v_code text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
    v_invite public.caregiver_invites%rowtype;
    v_link_id uuid;
begin
    if v_caregiver is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    if v_code !~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$' then
        raise exception 'invite_invalid_or_expired' using errcode = '22023';
    end if;
    select * into v_invite
    from public.caregiver_invites ci
    where ci.code_hash = encode(extensions.digest(v_code, 'sha256'), 'hex')
      and ci.claimed_at is null and ci.expires_at > now()
    for update;
    if not found then raise exception 'invite_invalid_or_expired' using errcode = '22023'; end if;
    if v_invite.owner_user_id = v_caregiver then raise exception 'cannot_link_self' using errcode = '22023'; end if;
    if v_invite.device_id is null then
        -- Replace any older per-device grants for this pair with the account-wide grant.
        update public.caregiver_links
        set status = 'revoked', revoked_at = now()
        where caregiver_user_id = v_caregiver and owner_user_id = v_invite.owner_user_id and status = 'active';
        insert into public.caregiver_links (caregiver_user_id, owner_user_id, device_id)
        values (v_caregiver, v_invite.owner_user_id, null)
        on conflict (caregiver_user_id, owner_user_id) where device_id is null
        do update set status = 'active', revoked_at = null
        returning id into v_link_id;
    else
        insert into public.caregiver_links (caregiver_user_id, owner_user_id, device_id)
        values (v_caregiver, v_invite.owner_user_id, v_invite.device_id)
        on conflict (caregiver_user_id, device_id) do update set status = 'active', revoked_at = null
        returning id into v_link_id;
    end if;
    update public.caregiver_invites set claimed_at = now() where id = v_invite.id;
    return query select v_link_id, v_invite.owner_user_id, v_invite.device_id, now();
end;
$$;

revoke all on function public.is_linked_caregiver(uuid, uuid) from public, anon;
grant execute on function public.is_linked_caregiver(uuid, uuid) to authenticated;
revoke all on function public.create_caregiver_account_invite() from public, anon;
grant execute on function public.create_caregiver_account_invite() to authenticated;
revoke all on function public.accept_caregiver_invite(text) from public, anon;
grant execute on function public.accept_caregiver_invite(text) to authenticated;
