create schema if not exists threadmind;

create type threadmind.action_status as enum ('draft', 'blocked', 'ready', 'confirmed', 'executing', 'succeeded', 'failed', 'cancelled');
create type threadmind.memory_status as enum ('active', 'superseded', 'deleted');

create table threadmind.action_cards (
  id uuid primary key,
  account_id uuid not null,
  submission_id uuid not null,
  action_type text not null check (action_type in ('create_meeting', 'create_contact', 'update_contact')),
  version integer not null check (version > 0),
  fields jsonb not null,
  evidence jsonb not null,
  target_account_id text,
  status threadmind.action_status not null,
  blockers jsonb not null default '[]',
  confirmed_snapshot jsonb,
  confirmed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, id),
  check ((status not in ('confirmed', 'executing', 'succeeded', 'failed')) or confirmed_snapshot is not null)
);

create table threadmind.action_receipts (
  id uuid primary key,
  account_id uuid not null,
  action_card_id uuid not null,
  confirmed_version integer not null,
  attempt integer not null check (attempt > 0),
  status text not null check (status in ('succeeded', 'failed', 'cancelled')),
  provider text not null check (provider in ('android_calendar', 'android_contacts')),
  target_record_id text,
  error_code text,
  error_message text,
  started_at timestamptz not null,
  completed_at timestamptz not null,
  foreign key (account_id, action_card_id) references threadmind.action_cards(account_id, id),
  unique (account_id, action_card_id, confirmed_version, attempt),
  check ((status = 'succeeded' and target_record_id is not null) or (status <> 'succeeded' and target_record_id is null))
);

create table threadmind.memory_records (
  id uuid primary key,
  account_id uuid not null,
  subject_refs jsonb not null default '[]',
  memory_type text not null check (memory_type in ('event', 'preference', 'relationship', 'commitment', 'profile', 'other')),
  assertion text not null,
  epistemic_status text not null check (epistemic_status in ('fact', 'inference')),
  confidence numeric(4,3) not null check (confidence between 0 and 1),
  sensitivity text not null check (sensitivity in ('normal', 'sensitive', 'highly_sensitive')),
  source_refs jsonb not null check (jsonb_array_length(source_refs) > 0),
  version integer not null check (version > 0),
  supersedes_id uuid,
  status threadmind.memory_status not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (supersedes_id) references threadmind.memory_records(id)
);

create index memory_active_account_idx on threadmind.memory_records (account_id, updated_at desc) where status = 'active';

create table threadmind.insight_bundles (
  id uuid primary key,
  account_id uuid not null,
  submission_id uuid not null,
  action_receipt_ids jsonb not null check (jsonb_array_length(action_receipt_ids) > 0),
  items jsonb not null,
  model_trace jsonb not null,
  generated_at timestamptz not null default now()
);

alter table threadmind.action_cards enable row level security;
alter table threadmind.action_receipts enable row level security;
alter table threadmind.memory_records enable row level security;
alter table threadmind.insight_bundles enable row level security;
alter table threadmind.action_cards force row level security;
alter table threadmind.action_receipts force row level security;
alter table threadmind.memory_records force row level security;
alter table threadmind.insight_bundles force row level security;
