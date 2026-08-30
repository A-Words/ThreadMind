create type threadmind.submission_status as enum (
  'uploaded', 'processing', 'ready', 'failed', 'deleted'
);
create type threadmind.job_status as enum (
  'queued', 'running', 'succeeded', 'failed', 'dead'
);

create table threadmind.screenshot_submissions (
  id uuid primary key,
  account_id uuid not null references auth.users (id) on delete cascade,
  image_object_path text not null,
  image_content_type text not null check (image_content_type in ('image/png', 'image/jpeg', 'image/webp')),
  image_byte_size bigint not null check (image_byte_size > 0 and image_byte_size <= 15728640),
  image_sha256 text not null check (image_sha256 ~ '^[0-9a-f]{64}$'),
  supplemental_text text check (supplemental_text is null or length(supplemental_text) <= 4000),
  submission_source text not null check (submission_source in ('in_app', 'android_share')),
  status threadmind.submission_status not null,
  failure_code text,
  processing_started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, id),
  unique (account_id, image_object_path),
  check (image_object_path = account_id::text || '/' || id::text),
  check (updated_at >= created_at),
  check ((status in ('ready', 'failed', 'deleted')) = (completed_at is not null)),
  check (status <> 'failed' or failure_code is not null)
);

create table threadmind.context_extractions (
  id uuid primary key,
  account_id uuid not null,
  submission_id uuid not null,
  messages jsonb not null check (jsonb_typeof(messages) = 'array'),
  participants jsonb not null check (jsonb_typeof(participants) = 'array'),
  entities jsonb not null check (jsonb_typeof(entities) = 'array'),
  temporal_expressions jsonb not null check (jsonb_typeof(temporal_expressions) = 'array'),
  action_candidates jsonb not null check (jsonb_typeof(action_candidates) = 'array'),
  evidence_spans jsonb not null check (jsonb_typeof(evidence_spans) = 'array'),
  warnings jsonb not null check (jsonb_typeof(warnings) = 'array'),
  model_trace jsonb not null check (jsonb_typeof(model_trace) = 'object'),
  created_at timestamptz not null default now(),
  unique (account_id, id),
  unique (account_id, submission_id),
  foreign key (account_id, submission_id)
    references threadmind.screenshot_submissions (account_id, id) on delete cascade
);

create table threadmind.background_jobs (
  id uuid primary key,
  account_id uuid not null,
  job_type text not null check (job_type in ('analyze_submission', 'delete_submission_artifacts')),
  aggregate_id uuid not null,
  idempotency_key text not null check (length(idempotency_key) between 1 and 200),
  status threadmind.job_status not null default 'queued',
  attempt integer not null default 0 check (attempt >= 0),
  max_attempts integer not null default 5 check (max_attempts between 1 and 20),
  available_at timestamptz not null default now(),
  locked_at timestamptz,
  locked_by text,
  last_error_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (account_id, id),
  unique (account_id, job_type, idempotency_key),
  foreign key (account_id, aggregate_id)
    references threadmind.screenshot_submissions (account_id, id) on delete cascade,
  check (updated_at >= created_at),
  check ((status = 'running') = (locked_at is not null and locked_by is not null))
);

alter table threadmind.action_cards
  add constraint action_cards_submission_fk
  foreign key (account_id, submission_id)
  references threadmind.screenshot_submissions (account_id, id) on delete cascade;

alter table threadmind.insight_bundles
  add constraint insight_bundles_submission_fk
  foreign key (account_id, submission_id)
  references threadmind.screenshot_submissions (account_id, id) on delete cascade;

create index screenshot_submissions_account_created_idx
  on threadmind.screenshot_submissions (account_id, created_at desc, id desc)
  where status <> 'deleted';
create index context_extractions_submission_idx
  on threadmind.context_extractions (account_id, submission_id);
create index background_jobs_claim_idx
  on threadmind.background_jobs (available_at, created_at, id)
  where status in ('queued', 'failed');
create index background_jobs_account_aggregate_idx
  on threadmind.background_jobs (account_id, aggregate_id, created_at desc);

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'threadmind_worker') then
    create role threadmind_worker nologin noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'threadmind_worker_runtime') then
    create role threadmind_worker_runtime login noinherit;
  end if;
end
$$;

grant usage on schema threadmind to threadmind_worker;
grant threadmind_api, threadmind_worker to threadmind_worker_runtime;
alter role threadmind_worker_runtime set statement_timeout = '30s';

grant select, insert, update, delete on
  threadmind.screenshot_submissions,
  threadmind.context_extractions
to threadmind_api;
grant select, insert on threadmind.background_jobs to threadmind_api;
grant select, update on threadmind.background_jobs to threadmind_worker;

revoke all on
  threadmind.screenshot_submissions,
  threadmind.context_extractions,
  threadmind.background_jobs
from anon, authenticated;

alter table threadmind.screenshot_submissions enable row level security;
alter table threadmind.context_extractions enable row level security;
alter table threadmind.background_jobs enable row level security;
alter table threadmind.screenshot_submissions force row level security;
alter table threadmind.context_extractions force row level security;
alter table threadmind.background_jobs force row level security;

create policy screenshot_submissions_account_select on threadmind.screenshot_submissions
  for select to threadmind_api using (account_id = (select threadmind.current_account_id()));
create policy screenshot_submissions_account_insert on threadmind.screenshot_submissions
  for insert to threadmind_api with check (account_id = (select threadmind.current_account_id()));
create policy screenshot_submissions_account_update on threadmind.screenshot_submissions
  for update to threadmind_api
  using (account_id = (select threadmind.current_account_id()))
  with check (account_id = (select threadmind.current_account_id()));
create policy screenshot_submissions_account_delete on threadmind.screenshot_submissions
  for delete to threadmind_api using (account_id = (select threadmind.current_account_id()));

create policy context_extractions_account_select on threadmind.context_extractions
  for select to threadmind_api using (account_id = (select threadmind.current_account_id()));
create policy context_extractions_account_insert on threadmind.context_extractions
  for insert to threadmind_api with check (account_id = (select threadmind.current_account_id()));
create policy context_extractions_account_update on threadmind.context_extractions
  for update to threadmind_api
  using (account_id = (select threadmind.current_account_id()))
  with check (account_id = (select threadmind.current_account_id()));
create policy context_extractions_account_delete on threadmind.context_extractions
  for delete to threadmind_api using (account_id = (select threadmind.current_account_id()));

create policy background_jobs_account_select on threadmind.background_jobs
  for select to threadmind_api using (account_id = (select threadmind.current_account_id()));
create policy background_jobs_account_insert on threadmind.background_jobs
  for insert to threadmind_api with check (account_id = (select threadmind.current_account_id()));
create policy background_jobs_worker_select on threadmind.background_jobs
  for select to threadmind_worker using (true);
create policy background_jobs_worker_update on threadmind.background_jobs
  for update to threadmind_worker using (true) with check (true);

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'threadmind-submissions',
  'threadmind-submissions',
  false,
  15728640,
  array['image/png', 'image/jpeg', 'image/webp']
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;
