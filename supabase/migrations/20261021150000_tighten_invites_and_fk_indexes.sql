-- Invite hashes are only accessed by the privileged RPCs; clients never query this table directly.
create policy caregiver_invites_no_direct_access on public.caregiver_invites
for all to authenticated using (false) with check (false);

-- RLS calls this helper as the signed-in user, so its read is also constrained by RLS on caregiver_links.
alter function public.is_linked_caregiver(uuid, uuid) security invoker;

-- Index composite ownership/device foreign keys used by deletes and joins.
create index caregiver_invites_owner_device_idx on public.caregiver_invites (owner_user_id, device_id);
create index emergency_alerts_user_device_idx on public.emergency_alerts (user_id, device_id);
create index emergency_alerts_user_fall_event_idx on public.emergency_alerts (user_id, fall_event_id);
create index fall_events_user_device_idx on public.fall_events (user_id, device_id);
create index measurements_user_device_idx on public.measurements (user_id, device_id);
create index notification_attempts_user_device_idx on public.notification_attempts (user_id, device_id);
