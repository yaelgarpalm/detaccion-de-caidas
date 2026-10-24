-- Keep only a low-rate movement summary for the protected person's phone.
-- Raw accelerometer traces remain on that phone; linked caregivers receive this summary.
create table if not exists public.phone_motion_status (
    device_id uuid primary key references public.devices(id) on delete cascade,
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    sample_count integer not null check (sample_count between 1 and 100000),
    average_dynamic_g double precision not null check (average_dynamic_g >= 0 and average_dynamic_g < 200),
    peak_dynamic_g double precision not null check (peak_dynamic_g >= 0 and peak_dynamic_g < 200),
    captured_at timestamptz not null,
    updated_at timestamptz not null default now()
);

alter table public.phone_motion_status enable row level security;

grant select, insert, update on table public.phone_motion_status to authenticated;

drop policy if exists phone_motion_status_select_owner_or_linked on public.phone_motion_status;
create policy phone_motion_status_select_owner_or_linked
    on public.phone_motion_status for select to authenticated
    using (user_id = (select auth.uid()) or public.is_linked_caregiver(user_id, device_id));

drop policy if exists phone_motion_status_insert_owner on public.phone_motion_status;
create policy phone_motion_status_insert_owner
    on public.phone_motion_status for insert to authenticated
    with check (user_id = (select auth.uid()));

drop policy if exists phone_motion_status_update_owner on public.phone_motion_status;
create policy phone_motion_status_update_owner
    on public.phone_motion_status for update to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'phone_motion_status'
    ) then
        alter publication supabase_realtime add table public.phone_motion_status;
    end if;
end;
$$;
