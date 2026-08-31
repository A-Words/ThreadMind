create function threadmind.is_active_session(p_account_id uuid, p_session_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from auth.sessions
    where id = p_session_id
      and user_id = p_account_id
  )
$$;

revoke all on function threadmind.is_active_session(uuid, uuid) from public;
grant execute on function threadmind.is_active_session(uuid, uuid) to threadmind_api;
