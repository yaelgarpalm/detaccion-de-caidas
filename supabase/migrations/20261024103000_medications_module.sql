begin;

create or replace function public.medication_schedule_times_valid(p_times jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $function$
declare
    v_count bigint;
    v_distinct_count bigint;
    v_invalid boolean;
begin
    if pg_catalog.jsonb_typeof(p_times) <> 'array' then return false; end if;
    v_count := pg_catalog.jsonb_array_length(p_times);
    if v_count < 1 or v_count > 12 then return false; end if;
    select pg_catalog.count(*), pg_catalog.count(distinct item.time_value),
           coalesce(pg_catalog.bool_or(item.time_value is null or item.time_value !~ '^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$'), true)
      into v_count, v_distinct_count, v_invalid
      from pg_catalog.jsonb_array_elements_text(p_times) as item(time_value);
    return not v_invalid and v_count = v_distinct_count;
end;
$function$;
revoke all on function public.medication_schedule_times_valid(jsonb) from public, anon;
grant execute on function public.medication_schedule_times_valid(jsonb) to authenticated;
grant execute on function public.is_linked_caregiver(uuid, uuid) to authenticated;

create table if not exists public.medications (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    name text not null check (char_length(btrim(name)) between 1 and 120),
    prescribed_dose text not null check (char_length(btrim(prescribed_dose)) between 1 and 120),
    presentation text not null default '' check (char_length(presentation) <= 80),
    frequency text not null check (frequency in ('Diaria', 'Cada 2 días', 'Cada 3 días', 'Semanal')),
    schedule_times jsonb not null check (
        public.medication_schedule_times_valid(schedule_times)
    ),
    start_date date not null,
    end_date date,
    instructions text not null default '' check (char_length(instructions) <= 500),
    is_active boolean not null default true,
    share_with_caregiver boolean not null default false,
    is_deleted boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint medications_end_after_start check (end_date is null or end_date >= start_date),
    constraint medications_id_owner_unique unique (id, owner_user_id)
);

create index if not exists medications_owner_active_updated_idx
    on public.medications (owner_user_id, is_deleted, is_active, updated_at desc);

create table if not exists public.medication_intakes (
    id uuid primary key default gen_random_uuid(),
    medication_id uuid not null,
    owner_user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    schedule_instance_id uuid not null,
    scheduled_at timestamptz not null,
    action_at timestamptz,
    status text not null check (status in ('pending', 'taken', 'skipped', 'overdue', 'snoozed', 'cancelled')),
    snoozed_until timestamptz,
    snooze_count smallint not null default 0 check (snooze_count between 0 and 1),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint medication_intakes_schedule_unique unique (owner_user_id, schedule_instance_id),
    constraint medication_intakes_medication_owner_fk foreign key (medication_id, owner_user_id)
        references public.medications (id, owner_user_id) on delete cascade,
    constraint medication_intakes_snooze_state check (
        (status = 'snoozed' and snoozed_until is not null and snooze_count = 1)
        or (status <> 'snoozed' and snooze_count in (0, 1))
    )
);

create index if not exists medication_intakes_owner_scheduled_idx
    on public.medication_intakes (owner_user_id, scheduled_at desc);
create index if not exists medication_intakes_medication_scheduled_idx
    on public.medication_intakes (medication_id, scheduled_at desc);

alter table public.medications enable row level security;
alter table public.medication_intakes enable row level security;

-- Explicit table grants keep this module accessible through the Data API for authenticated users.
grant select, insert, update, delete on public.medications to authenticated;
grant select, insert, update, delete on public.medication_intakes to authenticated;

drop policy if exists medications_select_owner_or_linked_caregiver on public.medications;
create policy medications_select_owner_or_linked_caregiver
    on public.medications for select to authenticated
    using (
        owner_user_id = (select auth.uid())
        or (
            share_with_caregiver
            and public.is_linked_caregiver(owner_user_id)
        )
    );

drop policy if exists medications_insert_owner on public.medications;
create policy medications_insert_owner
    on public.medications for insert to authenticated
    with check (owner_user_id = (select auth.uid()));

drop policy if exists medications_update_owner on public.medications;
create policy medications_update_owner
    on public.medications for update to authenticated
    using (owner_user_id = (select auth.uid()))
    with check (owner_user_id = (select auth.uid()));

drop policy if exists medications_delete_owner on public.medications;
create policy medications_delete_owner
    on public.medications for delete to authenticated
    using (owner_user_id = (select auth.uid()));

drop policy if exists medication_intakes_select_owner_or_linked_caregiver on public.medication_intakes;
create policy medication_intakes_select_owner_or_linked_caregiver
    on public.medication_intakes for select to authenticated
    using (
        owner_user_id = (select auth.uid())
        or (
            public.is_linked_caregiver(owner_user_id)
            and exists (
                select 1 from public.medications m
                where m.id = medication_intakes.medication_id
                  and m.owner_user_id = medication_intakes.owner_user_id
                  and m.share_with_caregiver
            )
        )
    );

drop policy if exists medication_intakes_insert_owner on public.medication_intakes;
create policy medication_intakes_insert_owner
    on public.medication_intakes for insert to authenticated
    with check (
        owner_user_id = (select auth.uid())
        and exists (
            select 1 from public.medications m
            where m.id = medication_id and m.owner_user_id = (select auth.uid())
        )
    );

drop policy if exists medication_intakes_update_owner on public.medication_intakes;
create policy medication_intakes_update_owner
    on public.medication_intakes for update to authenticated
    using (owner_user_id = (select auth.uid()))
    with check (
        owner_user_id = (select auth.uid())
        and exists (
            select 1 from public.medications m
            where m.id = medication_id and m.owner_user_id = (select auth.uid())
        )
    );

drop policy if exists medication_intakes_delete_owner on public.medication_intakes;
create policy medication_intakes_delete_owner
    on public.medication_intakes for delete to authenticated
    using (owner_user_id = (select auth.uid()));

commit;
