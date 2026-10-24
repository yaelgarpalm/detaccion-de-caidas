create extension if not exists pgcrypto with schema extensions;

create table public.profiles (
    user_id uuid primary key references auth.users (id) on delete cascade,
    full_name text not null default 'Usuario' check (char_length(full_name) between 1 and 120),
    role text check (role in ('wearer', 'caregiver')),
    preferences jsonb not null default '{}'::jsonb check (jsonb_typeof(preferences) = 'object'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    logical_id text not null check (char_length(logical_id) between 1 and 128),
    name text not null check (char_length(name) between 1 and 120),
    device_type text not null default 'unknown' check (device_type in ('phone', 'arduino', 'wearable', 'unknown')),
    link_status text not null default 'unknown' check (link_status in ('not_applicable', 'linked', 'unlinked', 'unknown')),
    last_connected_at timestamptz,
    last_sync_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, logical_id),
    unique (user_id, id)
);

create table public.trusted_contacts (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    name text not null check (char_length(name) between 1 and 120),
    phone text not null check (char_length(phone) between 7 and 20),
    preferences jsonb not null default '{}'::jsonb check (jsonb_typeof(preferences) = 'object'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, phone),
    unique (user_id, id)
);

create table public.caregiver_links (
    id uuid primary key default gen_random_uuid(),
    caregiver_user_id uuid not null references auth.users (id) on delete cascade,
    owner_user_id uuid not null references auth.users (id) on delete cascade,
    device_id uuid not null,
    status text not null default 'active' check (status in ('active', 'revoked')),
    created_at timestamptz not null default now(),
    revoked_at timestamptz,
    check (caregiver_user_id <> owner_user_id),
    foreign key (owner_user_id, device_id) references public.devices (user_id, id) on delete cascade,
    unique (caregiver_user_id, device_id)
);

create table public.caregiver_invites (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references auth.users (id) on delete cascade,
    device_id uuid not null,
    code_hash text not null unique,
    expires_at timestamptz not null,
    claimed_at timestamptz,
    foreign key (owner_user_id, device_id) references public.devices (user_id, id) on delete cascade
);

create table public.fall_events (
    id uuid primary key,
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    device_id uuid,
    occurred_at timestamptz not null,
    source text not null check (source in ('phone_accelerometer', 'camera_ai', 'manual', 'arduino', 'other')),
    classification text not null check (classification in ('possible_fall', 'fall', 'cancelled', 'false_alarm', 'manual', 'other')),
    confirmation_status text not null default 'unknown' check (confirmation_status in ('pending', 'confirmed', 'cancelled', 'false_alarm', 'unknown')),
    summary text check (summary is null or char_length(summary) <= 240),
    created_at timestamptz not null default now(),
    unique (user_id, id),
    foreign key (user_id, device_id) references public.devices (user_id, id) on delete cascade
);

create table public.emergency_alerts (
    id uuid primary key,
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    device_id uuid,
    fall_event_id uuid not null,
    status text not null check (status in ('created', 'cancelled', 'actions_requested', 'completed_locally', 'not_confirmed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, id),
    foreign key (user_id, device_id) references public.devices (user_id, id) on delete cascade,
    foreign key (user_id, fall_event_id) references public.fall_events (user_id, id) on delete cascade
);

create table public.notification_attempts (
    id uuid primary key,
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    device_id uuid,
    alert_id uuid not null,
    channel text not null check (channel in ('sms', 'call', 'push', 'other')),
    status text not null check (status in ('requested', 'accepted_by_os', 'sent_to_network', 'delivered', 'failed', 'not_confirmed')),
    attempted_at timestamptz not null,
    confirmed_at timestamptz,
    error_code text check (error_code is null or char_length(error_code) <= 80),
    foreign key (user_id, device_id) references public.devices (user_id, id) on delete cascade,
    foreign key (user_id, alert_id) references public.emergency_alerts (user_id, id) on delete cascade
);

create table public.measurements (
    id uuid primary key,
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    device_id uuid,
    measurement_type text not null check (measurement_type in ('heart_rate', 'spo2')),
    value double precision not null check (value > 0 and value < 1000),
    unit text not null check (unit in ('bpm', 'percent')),
    captured_at timestamptz not null,
    source text not null check (source in ('arduino', 'ble', 'other')),
    is_valid boolean,
    created_at timestamptz not null default now(),
    foreign key (user_id, device_id) references public.devices (user_id, id) on delete cascade
);

create index devices_user_updated_idx on public.devices (user_id, updated_at desc);
create index contacts_user_updated_idx on public.trusted_contacts (user_id, updated_at desc);
create index caregiver_links_owner_idx on public.caregiver_links (owner_user_id, device_id) where status = 'active';
create index fall_events_user_time_idx on public.fall_events (user_id, occurred_at desc);
create index emergency_alerts_user_time_idx on public.emergency_alerts (user_id, created_at desc);
create index notification_attempts_alert_time_idx on public.notification_attempts (user_id, alert_id, attempted_at desc);
create index measurements_user_time_idx on public.measurements (user_id, captured_at desc);

create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create trigger profiles_set_updated_at before update on public.profiles
for each row execute function public.set_updated_at();
create trigger devices_set_updated_at before update on public.devices
for each row execute function public.set_updated_at();
create trigger contacts_set_updated_at before update on public.trusted_contacts
for each row execute function public.set_updated_at();
create trigger emergency_alerts_set_updated_at before update on public.emergency_alerts
for each row execute function public.set_updated_at();

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (user_id, full_name)
    values (new.id, coalesce(nullif(trim(new.raw_user_meta_data ->> 'full_name'), ''), 'Usuario'))
    on conflict (user_id) do nothing;
    return new;
end;
$$;

create trigger on_auth_user_created after insert on auth.users
for each row execute function public.handle_new_user();

create or replace function public.is_linked_caregiver(p_owner_user_id uuid, p_device_id uuid default null)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.caregiver_links cl
        where cl.caregiver_user_id = (select auth.uid())
          and cl.owner_user_id = p_owner_user_id
          and cl.status = 'active'
          and (p_device_id is null or cl.device_id = p_device_id)
    );
$$;

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

create or replace function public.accept_caregiver_invite(p_code text)
returns table (link_id uuid, owner_user_id uuid, device_id uuid, linked_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caregiver uuid := auth.uid();
    v_invite public.caregiver_invites%rowtype;
    v_link_id uuid;
begin
    if v_caregiver is null then raise exception 'authentication_required' using errcode = '28000'; end if;
    select * into v_invite
    from public.caregiver_invites ci
    where ci.code_hash = encode(extensions.digest(upper(regexp_replace(coalesce(p_code, ''), '[^A-Fa-f0-9]', '', 'g')), 'sha256'), 'hex')
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

create or replace function public.unlink_caregiver(p_link_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.caregiver_links cl
    set status = 'revoked', revoked_at = now()
    where cl.id = p_link_id
      and (cl.owner_user_id = auth.uid() or cl.caregiver_user_id = auth.uid());
    if not found then raise exception 'link_not_found' using errcode = '42501'; end if;
end;
$$;

alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.trusted_contacts enable row level security;
alter table public.caregiver_links enable row level security;
alter table public.caregiver_invites enable row level security;
alter table public.fall_events enable row level security;
alter table public.emergency_alerts enable row level security;
alter table public.notification_attempts enable row level security;
alter table public.measurements enable row level security;

create policy profiles_select_owner_or_linked on public.profiles for select to authenticated
using (user_id = (select auth.uid()) or public.is_linked_caregiver(user_id));
create policy profiles_update_owner on public.profiles for update to authenticated
using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy devices_select_owner_or_linked on public.devices for select to authenticated
using (user_id = (select auth.uid()) or public.is_linked_caregiver(user_id, id));
create policy devices_insert_owner on public.devices for insert to authenticated
with check (user_id = (select auth.uid()));
create policy devices_update_owner on public.devices for update to authenticated
using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy devices_delete_owner on public.devices for delete to authenticated
using (user_id = (select auth.uid()));

create policy contacts_select_owner on public.trusted_contacts for select to authenticated
using (user_id = (select auth.uid()));
create policy contacts_insert_owner on public.trusted_contacts for insert to authenticated
with check (user_id = (select auth.uid()));
create policy contacts_update_owner on public.trusted_contacts for update to authenticated
using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy contacts_delete_owner on public.trusted_contacts for delete to authenticated
using (user_id = (select auth.uid()));

create policy caregiver_links_select_members on public.caregiver_links for select to authenticated
using ((caregiver_user_id = (select auth.uid()) or owner_user_id = (select auth.uid())) and status = 'active');
create policy fall_events_select_owner_or_linked on public.fall_events for select to authenticated
using (user_id = (select auth.uid()) or public.is_linked_caregiver(user_id, device_id));
create policy fall_events_insert_owner on public.fall_events for insert to authenticated
with check (user_id = (select auth.uid()));

create policy emergency_alerts_select_owner_or_linked on public.emergency_alerts for select to authenticated
using (user_id = (select auth.uid()) or public.is_linked_caregiver(user_id, device_id));
create policy emergency_alerts_insert_owner on public.emergency_alerts for insert to authenticated
with check (user_id = (select auth.uid()));
create policy emergency_alerts_update_owner on public.emergency_alerts for update to authenticated
using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy notification_attempts_select_owner_or_linked on public.notification_attempts for select to authenticated
using (user_id = (select auth.uid()) or public.is_linked_caregiver(user_id, device_id));
create policy notification_attempts_insert_owner on public.notification_attempts for insert to authenticated
with check (user_id = (select auth.uid()));

create policy measurements_select_owner_or_linked on public.measurements for select to authenticated
using (user_id = (select auth.uid()) or public.is_linked_caregiver(user_id, device_id));
create policy measurements_insert_owner on public.measurements for insert to authenticated
with check (user_id = (select auth.uid()));

revoke all on public.profiles, public.devices, public.trusted_contacts, public.caregiver_links,
    public.caregiver_invites, public.fall_events, public.emergency_alerts,
    public.notification_attempts, public.measurements from anon, authenticated;
grant select on public.profiles to authenticated;
grant update (full_name, role, preferences) on public.profiles to authenticated;
grant select, insert, update, delete on public.devices to authenticated;
grant select, insert, update, delete on public.trusted_contacts to authenticated;
grant select on public.caregiver_links to authenticated;
grant select, insert on public.fall_events to authenticated;
grant select, insert, update on public.emergency_alerts to authenticated;
grant select, insert on public.notification_attempts to authenticated;
grant select, insert on public.measurements to authenticated;

revoke all on function public.set_updated_at() from public, anon, authenticated;
revoke all on function public.handle_new_user() from public, anon, authenticated;
revoke all on function public.is_linked_caregiver(uuid, uuid) from public, anon;
grant execute on function public.is_linked_caregiver(uuid, uuid) to authenticated;
revoke all on function public.create_caregiver_invite(uuid) from public, anon;
grant execute on function public.create_caregiver_invite(uuid) to authenticated;
revoke all on function public.accept_caregiver_invite(text) from public, anon;
grant execute on function public.accept_caregiver_invite(text) to authenticated;
revoke all on function public.unlink_caregiver(uuid) from public, anon;
grant execute on function public.unlink_caregiver(uuid) to authenticated;

