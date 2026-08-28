do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'threadmind_runtime') then
    create role threadmind_runtime login noinherit;
  end if;
end
$$;

grant threadmind_api to threadmind_runtime;
alter role threadmind_runtime set statement_timeout = '5s';
