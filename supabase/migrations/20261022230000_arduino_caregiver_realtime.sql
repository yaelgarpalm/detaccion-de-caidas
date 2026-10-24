-- Stream UNO Q pulse, SpO2, and MPU fall records to linked caregivers.
-- RLS on both tables already limits reads to the owner and linked caregiver.
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'measurements'
    ) then
        alter publication supabase_realtime add table public.measurements;
    end if;

    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'fall_events'
    ) then
        alter publication supabase_realtime add table public.fall_events;
    end if;
end;
$$;
