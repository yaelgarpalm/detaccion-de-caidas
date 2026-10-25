-- Fix camera pairing re-link conflicts, return owner name, and allow wearer to manage/unlink cameras.

create or replace function public.accept_camera_device_invite(p_code text)
returns table (owner_user_id uuid, device_id uuid, device_name text, owner_name text, linked_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
    v_camera uuid := auth.uid();
    v_code text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
    v_invite public.camera_device_invites%rowtype;
    v_device uuid := extensions.gen_random_uuid();
    v_owner_name text := '';
    v_now timestamptz := clock_timestamp();
begin
    if v_camera is null then
        raise exception 'authentication_required' using errcode = '28000';
    end if;

    if v_code !~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$' then
        raise exception 'camera_invite_invalid_or_expired' using errcode = '22023';
    end if;

    select * into v_invite from public.camera_device_invites as i
    where i.code_hash = encode(extensions.digest(v_code, 'sha256'), 'hex')
      and i.claimed_at is null and i.expires_at > v_now
    for update;

    if not found then
        raise exception 'camera_invite_invalid_or_expired' using errcode = '22023';
    end if;

    select coalesce(p.full_name, 'Persona protegida') into v_owner_name
    from public.profiles as p
    where p.user_id = v_invite.owner_user_id;

    if v_owner_name is null or trim(v_owner_name) = '' then
        v_owner_name := 'Persona protegida';
    end if;

    insert into public.devices (id, user_id, logical_id, name, device_type, link_status, last_connected_at, updated_at)
    values (v_device, v_invite.owner_user_id, 'camera-user:' || v_camera::text, v_invite.device_name, 'camera', 'linked', v_now, v_now)
    on conflict (user_id, logical_id) do update
        set name = excluded.name,
            device_type = excluded.device_type,
            link_status = 'linked',
            last_connected_at = v_now,
            updated_at = v_now
    returning id into v_device;

    -- Clean up any prior camera link for this camera account or for this owner/device
    delete from public.camera_device_links
    where camera_user_id = v_camera
       or (owner_user_id = v_invite.owner_user_id and device_id = v_device);

    insert into public.camera_device_links (camera_user_id, owner_user_id, device_id, linked_at)
    values (v_camera, v_invite.owner_user_id, v_device, v_now);

    update public.camera_device_invites as cdi
    set claimed_at = v_now, claimed_by = v_camera
    where cdi.id = v_invite.id;

    return query select v_invite.owner_user_id, v_device, v_invite.device_name, v_owner_name, v_now;
end;
$$;

create or replace function public.unlink_camera_device_by_owner(p_device_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception 'authentication_required' using errcode = '28000';
    end if;

    update public.devices set link_status = 'unlinked', updated_at = clock_timestamp()
    where user_id = auth.uid() and id = p_device_id and device_type = 'camera';

    delete from public.camera_device_links
    where owner_user_id = auth.uid() and device_id = p_device_id;
end;
$$;

revoke all on function public.accept_camera_device_invite(text) from public, anon;
grant execute on function public.accept_camera_device_invite(text) to authenticated;

revoke all on function public.unlink_camera_device_by_owner(uuid) from public, anon;
grant execute on function public.unlink_camera_device_by_owner(uuid) to authenticated;

notify pgrst, 'reload schema';
