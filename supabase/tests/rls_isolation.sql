-- Run only against an empty/local test database, never against a database with user data.
-- The transaction rollback removes fixture accounts and rows.
begin;
insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values
  ('10000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'rls-owner@example.invalid', '', now(), now(), now()),
  ('20000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated', 'rls-other@example.invalid', '', now(), now(), now());

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}', true);
insert into public.devices (id, logical_id, name, device_type)
values ('30000000-0000-4000-8000-000000000003', 'rls-test-device', 'Test device', 'phone');
insert into public.trusted_contacts (id, name, phone)
values ('40000000-0000-4000-8000-000000000004', 'Test contact', '+15555550100');
insert into public.fall_events (id, device_id, occurred_at, source, classification, confirmation_status)
values ('50000000-0000-4000-8000-000000000005', '30000000-0000-4000-8000-000000000003', now(), 'manual', 'manual', 'confirmed');
insert into public.measurements (id, device_id, measurement_type, value, unit, captured_at, source, is_valid)
values ('60000000-0000-4000-8000-000000000006', '30000000-0000-4000-8000-000000000003', 'heart_rate', 72, 'bpm', now(), 'arduino', true);
insert into public.emergency_alerts (id, device_id, fall_event_id, status)
values ('70000000-0000-4000-8000-000000000007', '30000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000005', 'created');
insert into public.notification_attempts (id, device_id, alert_id, channel, status, attempted_at)
values ('80000000-0000-4000-8000-000000000008', '30000000-0000-4000-8000-000000000003', '70000000-0000-4000-8000-000000000007', 'sms', 'requested', now());
do $$ begin
  if (select count(*) from public.devices) <> 1 then raise exception 'owner cannot read own device'; end if;
  if (select count(*) from public.trusted_contacts) <> 1 then raise exception 'owner cannot read own contact'; end if;
  if (select count(*) from public.fall_events) <> 1 then raise exception 'owner cannot read own event'; end if;
  if (select count(*) from public.measurements) <> 1 then raise exception 'owner cannot read own measurement'; end if;
  if (select count(*) from public.emergency_alerts) <> 1 then raise exception 'owner cannot read own alert'; end if;
  if (select count(*) from public.notification_attempts) <> 1 then raise exception 'owner cannot read own notification attempt'; end if;
end $$;

select set_config('request.jwt.claims', '{"sub":"20000000-0000-4000-8000-000000000002","role":"authenticated"}', true);
do $$ begin
  if (select count(*) from public.devices) <> 0 then raise exception 'cross-account device read'; end if;
  if (select count(*) from public.trusted_contacts) <> 0 then raise exception 'cross-account contact read'; end if;
  if (select count(*) from public.fall_events) <> 0 then raise exception 'cross-account event read'; end if;
  if (select count(*) from public.measurements) <> 0 then raise exception 'cross-account measurement read'; end if;
  if (select count(*) from public.emergency_alerts) <> 0 then raise exception 'cross-account alert read'; end if;
  if (select count(*) from public.notification_attempts) <> 0 then raise exception 'cross-account notification attempt read'; end if;
  if (select count(*) from public.profiles where user_id = '10000000-0000-4000-8000-000000000001') <> 0 then raise exception 'cross-account profile read'; end if;
  begin
    insert into public.devices (user_id, logical_id, name, device_type)
    values ('10000000-0000-4000-8000-000000000001', 'forged', 'Forged owner', 'phone');
    raise exception using errcode = 'ZX001', message = 'cross-account device insert unexpectedly succeeded';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.trusted_contacts (user_id, name, phone)
    values ('10000000-0000-4000-8000-000000000001', 'Forged', '+15555550101');
    raise exception using errcode = 'ZX001', message = 'cross-account contact insert unexpectedly succeeded';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;
