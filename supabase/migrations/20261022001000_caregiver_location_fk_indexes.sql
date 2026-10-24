-- Support the caregiver_locations foreign keys and owner-scoped reads.
create index if not exists caregiver_locations_owner_device_idx
    on public.caregiver_locations (owner_user_id, device_id);
