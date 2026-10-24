-- Add the dedicated camera access flow and one-time camera pairing codes.
alter table public.profiles drop constraint if exists profiles_role_check;
alter table public.profiles add constraint profiles_role_check
    check (role in ('wearer', 'caregiver', 'camera'));

create table if not exists public.camera_device_invites (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references auth.users (id) on delete cascade,
    device_name text not null check (char_length(device_name) between 1 and 120),
    code_hash text not null unique,
    expires_at timestamptz not null,
    claimed_at timestamptz,
    claimed_by uuid references auth.users (id) on delete set null,
    created_at timestamptz not null default now()
);

create table if not exists public.camera_device_links (
    id uuid primary key default gen_random_uuid(),
    camera_user_id uuid not null references auth.users (id) on delete cascade,
    owner_user_id uuid not null references auth.users (id) on delete cascade,
    device_id uuid not null,
    linked_at timestamptz not null default now(),
    unique (camera_user_id),
    unique (owner_user_id, device_id),
    foreign key (owner_user_id, device_id) references public.devices (user_id, id) on delete cascade
);

alter table public.camera_device_invites enable row level security;
alter table public.camera_device_links enable row level security;
revoke all on public.camera_device_invites, public.camera_device_links from anon, authenticated;

create or replace function public.create_camera_device_invite(p_device_name text)
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
    v_name text := left(trim(coalesce(p_device_name, '')), 120);
begin
    if v_owner is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    if v_name = '' then raise exception 'device_name_required' using errcode = '22023'; end if;
    delete from public.camera_device_invites where owner_user_id = v_owner and claimed_at is null;
    for v_index in 0..7 loop
        v_code := v_code || substr(v_alphabet, (get_byte(v_bytes, v_index) % 32) + 1, 1);
    end loop;
    insert into public.camera_device_invites (owner_user_id, device_name, code_hash, expires_at)
    values (v_owner, v_name, encode(extensions.digest(v_code, 'sha256'), 'hex'), v_expiry);
    return query select substr(v_code, 1, 4) || '-' || substr(v_code, 5, 4), v_expiry;
end;
$$;

create or replace function public.accept_camera_device_invite(p_code text)
returns table (owner_user_id uuid, device_id uuid, device_name text, linked_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_camera uuid := auth.uid();
    v_code text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
    v_invite public.camera_device_invites%rowtype;
    v_device uuid := extensions.gen_random_uuid();
begin
    if v_camera is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    if v_code !~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$' then
        raise exception 'camera_invite_invalid_or_expired' using errcode = '22023';
    end if;
    select * into v_invite from public.camera_device_invites i
    where i.code_hash = encode(extensions.digest(v_code, 'sha256'), 'hex')
      and i.claimed_at is null and i.expires_at > now()
    for update;
    if not found then raise exception 'camera_invite_invalid_or_expired' using errcode = '22023'; end if;
    if v_invite.owner_user_id = v_camera then raise exception 'cannot_link_camera_to_same_account' using errcode = '22023'; end if;

    insert into public.devices (id, user_id, logical_id, name, device_type, link_status, last_connected_at)
    values (v_device, v_invite.owner_user_id, 'camera-user:' || v_camera::text, v_invite.device_name, 'camera', 'linked', now())
    on conflict (user_id, logical_id) do update
        set name = excluded.name, device_type = excluded.device_type,
            link_status = 'linked', last_connected_at = now()
    returning id into v_device;
    insert into public.camera_device_links (camera_user_id, owner_user_id, device_id)
    values (v_camera, v_invite.owner_user_id, v_device);
    update public.camera_device_invites set claimed_at = now(), claimed_by = v_camera where id = v_invite.id;
    return query select v_invite.owner_user_id, v_device, v_invite.device_name, now();
end;
$$;

create or replace function public.unlink_paired_camera()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_link public.camera_device_links%rowtype;
begin
    if auth.uid() is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    select * into v_link from public.camera_device_links where camera_user_id = auth.uid() for update;
    if not found then return; end if;
    update public.devices set link_status = 'unlinked', updated_at = now()
    where user_id = v_link.owner_user_id and id = v_link.device_id;
    delete from public.camera_device_links where id = v_link.id;
end;
$$;

create or replace function public.submit_paired_camera_fall_event(
    p_event_id uuid, p_occurred_at timestamptz, p_summary text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_link public.camera_device_links%rowtype;
begin
    if auth.uid() is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    select * into v_link from public.camera_device_links where camera_user_id = auth.uid();
    if not found then raise exception 'camera_not_linked' using errcode = '42501'; end if;
    insert into public.fall_events (id, user_id, device_id, occurred_at, source, classification, confirmation_status, summary)
    values (p_event_id, v_link.owner_user_id, v_link.device_id, p_occurred_at, 'camera_ai', 'possible_fall', 'pending', left(p_summary, 240))
    on conflict (id) do nothing;
end;
$$;

revoke all on function public.create_camera_device_invite(text) from public, anon;
grant execute on function public.create_camera_device_invite(text) to authenticated;
revoke all on function public.accept_camera_device_invite(text) from public, anon;
grant execute on function public.accept_camera_device_invite(text) to authenticated;
revoke all on function public.unlink_paired_camera() from public, anon;
grant execute on function public.unlink_paired_camera() to authenticated;
revoke all on function public.submit_paired_camera_fall_event(uuid, timestamptz, text) from public, anon;
grant execute on function public.submit_paired_camera_fall_event(uuid, timestamptz, text) to authenticated;


-- Index invite ownership and claim lookups without exposing invite rows.
create index if not exists camera_device_invites_owner_idx
    on public.camera_device_invites (owner_user_id);

create index if not exists camera_device_invites_claimed_by_idx
    on public.camera_device_invites (claimed_by);

-- Ensure PostgREST exposes the freshly installed camera pairing RPCs immediately.
notify pgrst, 'reload schema';

