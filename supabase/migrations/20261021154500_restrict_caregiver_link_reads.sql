drop policy if exists caregiver_links_delete_members on public.caregiver_links;
drop policy if exists caregiver_links_select_members on public.caregiver_links;
create policy caregiver_links_select_active_members on public.caregiver_links for select to authenticated
using ((caregiver_user_id = (select auth.uid()) or owner_user_id = (select auth.uid())) and status = 'active');
revoke delete on public.caregiver_links from authenticated;
