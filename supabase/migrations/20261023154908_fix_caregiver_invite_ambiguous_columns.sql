-- Avoid collisions between RETURNS TABLE output names and caregiver_links columns.
-- In PL/pgSQL, unqualified owner_user_id can mean either the table column or
-- the function's output variable. Qualifying the UPDATE makes account linking
-- deterministic for account-wide invitations (device_id IS NULL).
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
    if v_caregiver is null then
        raise exception 'authentication_required' using errcode = '28000';
    end if;

    if v_code !~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$' then
        raise exception 'invite_invalid_or_expired' using errcode = '22023';
    end if;

    select ci.* into v_invite
    from public.caregiver_invites as ci
    where ci.code_hash = encode(extensions.digest(v_code, 'sha256'), 'hex')
      and ci.claimed_at is null
      and ci.expires_at > now()
    for update;

    if not found then
        raise exception 'invite_invalid_or_expired' using errcode = '22023';
    end if;

    if v_invite.owner_user_id = v_caregiver then
        raise exception 'cannot_link_self' using errcode = '22023';
    end if;

    if v_invite.device_id is null then
        update public.caregiver_links as existing_link
        set status = 'revoked', revoked_at = now()
        where existing_link.caregiver_user_id = v_caregiver
          and existing_link.owner_user_id = v_invite.owner_user_id
          and existing_link.status = 'active';

        insert into public.caregiver_links (caregiver_user_id, owner_user_id, device_id)
        values (v_caregiver, v_invite.owner_user_id, null)
        on conflict (caregiver_user_id, owner_user_id) where device_id is null
        do update set status = 'active', revoked_at = null
        returning id into v_link_id;
    else
        insert into public.caregiver_links (caregiver_user_id, owner_user_id, device_id)
        values (v_caregiver, v_invite.owner_user_id, v_invite.device_id)
        on conflict (caregiver_user_id, device_id)
        do update set status = 'active', revoked_at = null
        returning id into v_link_id;
    end if;

    update public.caregiver_invites as ci
    set claimed_at = now()
    where ci.id = v_invite.id;

    return query select v_link_id, v_invite.owner_user_id, v_invite.device_id, now();
end;
$$;

revoke all on function public.accept_caregiver_invite(text) from public, anon;
grant execute on function public.accept_caregiver_invite(text) to authenticated;
