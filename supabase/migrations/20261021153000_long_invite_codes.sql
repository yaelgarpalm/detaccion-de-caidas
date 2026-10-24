create or replace function public.create_caregiver_invite(p_device_id uuid)
returns table (code text, expires_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner uuid := auth.uid();
    v_code text;
    v_expiry timestamptz := now() + interval '10 minutes';
begin
    if v_owner is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    if not exists (select 1 from public.devices d where d.id = p_device_id and d.user_id = v_owner) then
        raise exception 'device_not_owned' using errcode = '42501';
    end if;
    delete from public.caregiver_invites ci where ci.owner_user_id = v_owner and ci.claimed_at is null;
    v_code := upper(encode(extensions.gen_random_bytes(16), 'hex'));
    insert into public.caregiver_invites (owner_user_id, device_id, code_hash, expires_at)
    values (v_owner, p_device_id, encode(extensions.digest(v_code, 'sha256'), 'hex'), v_expiry);
    return query select v_code, v_expiry;
end;
$$;
