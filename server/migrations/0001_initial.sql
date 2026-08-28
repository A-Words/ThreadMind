create schema if not exists threadmind;
revoke all on schema threadmind from public;

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'threadmind_api') then
    create role threadmind_api nologin noinherit;
  end if;
end
$$;

grant usage on schema threadmind to threadmind_api;

create function threadmind.current_account_id()
returns uuid
language sql
stable
security invoker
set search_path = ''
as $$
  select nullif(current_setting('app.current_account_id', true), '')::uuid
$$;

revoke all on function threadmind.current_account_id() from public;
grant execute on function threadmind.current_account_id() to threadmind_api;

create type threadmind.action_status as enum (
  'draft', 'blocked', 'ready', 'confirmed', 'executing', 'succeeded', 'failed', 'cancelled'
);
create type threadmind.memory_status as enum ('active', 'superseded', 'deleted');

create table threadmind.action_cards (
  id uuid primary key,
  account_id uuid not null references auth.users (id) on delete cascade,
  submission_id uuid not null,
  action_type text not null check (action_type in ('create_meeting', 'create_contact', 'update_contact')),
  version integer not null check (version > 0),
  fields jsonb not null check (jsonb_typeof(fields) = 'object'),
  evidence jsonb not null check (jsonb_typeof(evidence) = 'array'),
  target_account_id text,
  status threadmind.action_status not null,
  blockers jsonb not null default '[]' check (jsonb_typeof(blockers) = 'array'),
  confirmed_snapshot jsonb,
  confirmed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, id),
  check ((status not in ('confirmed', 'executing', 'succeeded', 'failed')) or confirmed_snapshot is not null)
);

create table threadmind.action_receipts (
  id uuid primary key,
  account_id uuid not null references auth.users (id) on delete cascade,
  action_card_id uuid not null,
  confirmed_version integer not null check (confirmed_version > 0),
  attempt integer not null check (attempt > 0),
  status text not null check (status in ('succeeded', 'failed', 'cancelled')),
  provider text not null check (provider in ('android_calendar', 'android_contacts')),
  target_record_id text,
  error_code text,
  error_message text,
  started_at timestamptz not null,
  completed_at timestamptz not null,
  foreign key (account_id, action_card_id) references threadmind.action_cards (account_id, id) on delete cascade,
  unique (account_id, action_card_id, confirmed_version, attempt),
  check (completed_at >= started_at),
  check ((status = 'succeeded' and target_record_id is not null) or (status <> 'succeeded' and target_record_id is null))
);

create table threadmind.memory_records (
  id uuid primary key,
  account_id uuid not null references auth.users (id) on delete cascade,
  subject_refs jsonb not null default '[]' check (jsonb_typeof(subject_refs) = 'array'),
  memory_type text not null check (memory_type in ('event', 'preference', 'relationship', 'commitment', 'profile', 'other')),
  assertion text not null check (length(btrim(assertion)) > 0),
  epistemic_status text not null check (epistemic_status in ('fact', 'inference')),
  confidence numeric(4,3) not null check (confidence between 0 and 1),
  sensitivity text not null check (sensitivity in ('normal', 'sensitive', 'highly_sensitive')),
  source_refs jsonb not null check (jsonb_typeof(source_refs) = 'array' and jsonb_array_length(source_refs) > 0),
  version integer not null check (version > 0),
  supersedes_id uuid,
  status threadmind.memory_status not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, id),
  foreign key (account_id, supersedes_id) references threadmind.memory_records (account_id, id),
  check (updated_at >= created_at),
  check ((version = 1 and supersedes_id is null) or (version > 1 and supersedes_id is not null))
);

create table threadmind.insight_bundles (
  id uuid primary key,
  account_id uuid not null references auth.users (id) on delete cascade,
  submission_id uuid not null,
  action_receipt_ids jsonb not null check (jsonb_typeof(action_receipt_ids) = 'array' and jsonb_array_length(action_receipt_ids) > 0),
  items jsonb not null check (jsonb_typeof(items) = 'array'),
  model_trace jsonb not null check (jsonb_typeof(model_trace) = 'object'),
  generated_at timestamptz not null default now()
);

create index action_cards_account_updated_idx
  on threadmind.action_cards (account_id, updated_at desc, id desc);
create index action_receipts_card_idx
  on threadmind.action_receipts (account_id, action_card_id);
create index memory_active_account_updated_idx
  on threadmind.memory_records (account_id, updated_at desc, id desc)
  where status = 'active';
create index memory_supersedes_idx
  on threadmind.memory_records (account_id, supersedes_id)
  where supersedes_id is not null;
create index insight_bundles_account_generated_idx
  on threadmind.insight_bundles (account_id, generated_at desc, id desc);

grant select, insert, update on
  threadmind.action_cards,
  threadmind.action_receipts,
  threadmind.memory_records,
  threadmind.insight_bundles
to threadmind_api;

revoke all on
  threadmind.action_cards,
  threadmind.action_receipts,
  threadmind.memory_records,
  threadmind.insight_bundles
from anon, authenticated;

alter table threadmind.action_cards enable row level security;
alter table threadmind.action_receipts enable row level security;
alter table threadmind.memory_records enable row level security;
alter table threadmind.insight_bundles enable row level security;
alter table threadmind.action_cards force row level security;
alter table threadmind.action_receipts force row level security;
alter table threadmind.memory_records force row level security;
alter table threadmind.insight_bundles force row level security;

create policy action_cards_account_select on threadmind.action_cards
  for select to threadmind_api using (account_id = (select threadmind.current_account_id()));
create policy action_cards_account_insert on threadmind.action_cards
  for insert to threadmind_api with check (account_id = (select threadmind.current_account_id()));
create policy action_cards_account_update on threadmind.action_cards
  for update to threadmind_api
  using (account_id = (select threadmind.current_account_id()))
  with check (account_id = (select threadmind.current_account_id()));

create policy action_receipts_account_select on threadmind.action_receipts
  for select to threadmind_api using (account_id = (select threadmind.current_account_id()));
create policy action_receipts_account_insert on threadmind.action_receipts
  for insert to threadmind_api with check (account_id = (select threadmind.current_account_id()));
create policy action_receipts_account_update on threadmind.action_receipts
  for update to threadmind_api
  using (account_id = (select threadmind.current_account_id()))
  with check (account_id = (select threadmind.current_account_id()));

create policy memory_records_account_select on threadmind.memory_records
  for select to threadmind_api using (account_id = (select threadmind.current_account_id()));
create policy memory_records_account_insert on threadmind.memory_records
  for insert to threadmind_api with check (account_id = (select threadmind.current_account_id()));
create policy memory_records_account_update on threadmind.memory_records
  for update to threadmind_api
  using (account_id = (select threadmind.current_account_id()))
  with check (account_id = (select threadmind.current_account_id()));

create policy insight_bundles_account_select on threadmind.insight_bundles
  for select to threadmind_api using (account_id = (select threadmind.current_account_id()));
create policy insight_bundles_account_insert on threadmind.insight_bundles
  for insert to threadmind_api with check (account_id = (select threadmind.current_account_id()));
create policy insight_bundles_account_update on threadmind.insight_bundles
  for update to threadmind_api
  using (account_id = (select threadmind.current_account_id()))
  with check (account_id = (select threadmind.current_account_id()));
