-- Ensure all tables that the caregiver Realtime components subscribe to are
-- included in the supabase_realtime publication. These statements are idempotent:
-- they check pg_publication_tables before issuing ALTER PUBLICATION so the
-- migration is safe to apply more than once and will not fail if the table is
-- already published.

do $$
begin
    -- measurements: pulse/SpO2 readings from the bracelet or phone
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'measurements'
    ) then
        alter publication supabase_realtime add table public.measurements;
    end if;

    -- fall_events: fall detections from any source (bracelet, camera, phone)
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'fall_events'
    ) then
        alter publication supabase_realtime add table public.fall_events;
    end if;

    -- phone_motion_status: already added by 20261023161538 but guard is safe
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'phone_motion_status'
    ) then
        alter publication supabase_realtime add table public.phone_motion_status;
    end if;

    -- caregiver_locations: already added by 20261021170000 but guard is safe
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'caregiver_locations'
    ) then
        alter publication supabase_realtime add table public.caregiver_locations;
    end if;
end;
$$;

-- Add a composite index that speeds up the caregiver's initial REST snapshot
-- query: measurements filtered by user_id and device_id, ordered by captured_at desc.
-- CREATE INDEX IF NOT EXISTS is idempotent.
create index if not exists measurements_user_device_captured_idx
    on public.measurements (user_id, device_id, captured_at desc);

-- Same for fall_events: caregiver queries by user_id (and optionally device_id).
create index if not exists fall_events_user_device_occurred_idx
    on public.fall_events (user_id, device_id, occurred_at desc);
