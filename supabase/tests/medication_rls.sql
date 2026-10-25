-- Run only on the empty/local Supabase test database. The transaction is rolled back.
begin;
insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values
  ('91000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'med-owner@example.invalid', '', now(), now(), now()),
  ('92000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated', 'med-caregiver@example.invalid', '', now(), now(), now()),
  ('93000000-0000-4000-8000-000000000003', 'authenticated', 'authenticated', 'med-outsider@example.invalid', '', now(), now(), now());

insert into public.caregiver_links (caregiver_user_id, owner_user_id, device_id, status)
values ('92000000-0000-4000-8000-000000000002', '91000000-0000-4000-8000-000000000001', null, 'active');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"91000000-0000-4000-8000-000000000001","role":"authenticated"}', true);
insert into public.medications (
  id, owner_user_id, name, prescribed_dose, presentation, frequency, schedule_times, start_date, share_with_caregiver
) values (
  '94000000-0000-4000-8000-000000000004', '91000000-0000-4000-8000-000000000001',
  'Medication RLS fixture', 'as prescribed', '', 'Diaria', '["08:00","20:00"]', current_date, true
), (
  '97000000-0000-4000-8000-000000000007', '91000000-0000-4000-8000-000000000001',
  'Private medication fixture', 'as prescribed', '', 'Diaria', '["09:00"]', current_date, false
);
insert into public.medication_intakes (
  id, medication_id, owner_user_id, schedule_instance_id, scheduled_at, status
) values (
  '95000000-0000-4000-8000-000000000005', '94000000-0000-4000-8000-000000000004',
  '91000000-0000-4000-8000-000000000001', '96000000-0000-4000-8000-000000000006', now(), 'pending'
), (
  '98000000-0000-4000-8000-000000000008', '97000000-0000-4000-8000-000000000007',
  '91000000-0000-4000-8000-000000000001', '99000000-0000-4000-8000-000000000009', now(), 'pending'
);
do $$ begin
  if (select count(*) from public.medications) <> 2 then raise exception 'owner cannot read own medications'; end if;
  if (select count(*) from public.medication_intakes) <> 2 then raise exception 'owner cannot read own intakes'; end if;
  begin
    insert into public.medications (owner_user_id, name, prescribed_dose, frequency, schedule_times, start_date)
    values ('91000000-0000-4000-8000-000000000001', 'Invalid schedule', 'as prescribed', 'Diaria', '["25:00"]', current_date);
    raise exception using errcode = 'ZX001', message = 'invalid schedule time unexpectedly accepted';
  exception when check_violation then null;
  end;
end $$;

select set_config('request.jwt.claims', '{"sub":"92000000-0000-4000-8000-000000000002","role":"authenticated"}', true);
do $$
declare affected integer;
begin
  if (select count(*) from public.medications) <> 1 then raise exception 'caregiver read must require medication sharing consent'; end if;
  if (select count(*) from public.medication_intakes) <> 1 then raise exception 'caregiver intake read must require medication sharing consent'; end if;
  update public.medications set name = 'caregiver modification' where id = '94000000-0000-4000-8000-000000000004';
  get diagnostics affected = row_count;
  if affected <> 0 then raise exception 'caregiver unexpectedly updated medication'; end if;
  update public.medication_intakes set status = 'taken' where id = '95000000-0000-4000-8000-000000000005';
  get diagnostics affected = row_count;
  if affected <> 0 then raise exception 'caregiver unexpectedly changed intake status'; end if;
  begin
    insert into public.medications (owner_user_id, name, prescribed_dose, frequency, schedule_times, start_date)
    values ('91000000-0000-4000-8000-000000000001', 'Caregiver forged medication', 'as prescribed', 'Diaria', '["08:00"]', current_date);
    raise exception using errcode = 'ZX001', message = 'caregiver unexpectedly inserted a medication for another user';
  exception when insufficient_privilege then null;
  end;
end $$;

select set_config('request.jwt.claims', '{"sub":"93000000-0000-4000-8000-000000000003","role":"authenticated"}', true);
do $$ begin
  if (select count(*) from public.medications) <> 0 then raise exception 'unlinked user read another account medication'; end if;
  if (select count(*) from public.medication_intakes) <> 0 then raise exception 'unlinked user read another account intake'; end if;
end $$;
rollback;
