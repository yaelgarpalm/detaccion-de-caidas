create index if not exists phone_motion_status_user_captured_idx
    on public.phone_motion_status (user_id, captured_at desc);
