create table public.caregiver_locations (
    device_id uuid primary key,
    owner_user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    accuracy_m double precision check (accuracy_m is null or accuracy_m between 0 and 100000),
    captured_at timestamptz not null,
    updated_at timestamptz not null default now(),
    foreign key (owner_user_id, device_id) references public.devices (user_id, id) on delete cascade
);

alter table public.caregiver_locations enable row level security;
create policy caregiver_locations_select_owner_or_linked on public.caregiver_locations
    for select to authenticated
    using (owner_user_id = (select auth.uid()) or public.is_linked_caregiver(owner_user_id, device_id));
create policy caregiver_locations_insert_owner on public.caregiver_locations
    for insert to authenticated
    with check (owner_user_id = (select auth.uid()));
create policy caregiver_locations_update_owner on public.caregiver_locations
    for update to authenticated
    using (owner_user_id = (select auth.uid()))
    with check (owner_user_id = (select auth.uid()));
create policy caregiver_locations_delete_owner on public.caregiver_locations
    for delete to authenticated
    using (owner_user_id = (select auth.uid()));
grant select, insert, update, delete on public.caregiver_locations to authenticated;

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'caregiver_locations'
    ) then
        alter publication supabase_realtime add table public.caregiver_locations;
    end if;
end;
$$;

create or replace function public.create_caregiver_invite(p_device_id uuid)
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
    if not exists (select 1 from public.devices d where d.id = p_device_id and d.user_id = v_owner) then
        raise exception 'device_not_owned' using errcode = '42501';
    end if;
    delete from public.caregiver_invites ci where ci.owner_user_id = v_owner and ci.claimed_at is null;
    for v_index in 0..7 loop
        v_code := v_code || substr(v_alphabet, (get_byte(v_bytes, v_index) % 32) + 1, 1);
    end loop;
    insert into public.caregiver_invites (owner_user_id, device_id, code_hash, expires_at)
    values (v_owner, p_device_id, encode(extensions.digest(v_code, 'sha256'), 'hex'), v_expiry);
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
    insert into public.caregiver_links (caregiver_user_id, owner_user_id, device_id)
    values (v_caregiver, v_invite.owner_user_id, v_invite.device_id)
    on conflict (caregiver_user_id, device_id) do update set status = 'active', revoked_at = null
    returning id into v_link_id;
    update public.caregiver_invites set claimed_at = now() where id = v_invite.id;
    return query select v_link_id, v_invite.owner_user_id, v_invite.device_id, now();
end;
$$;

revoke all on function public.create_caregiver_invite(uuid) from public, anon;
revoke all on function public.accept_caregiver_invite(text) from public, anon;
grant execute on function public.create_caregiver_invite(uuid) to authenticated;
grant execute on function public.accept_caregiver_invite(text) to authenticated;
