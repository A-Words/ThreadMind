alter table threadmind.insight_bundles
  add column generation_key text;

update threadmind.insight_bundles
set generation_key = 'legacy:' || id::text
where generation_key is null;

delete from threadmind.insight_bundles insight
where not exists (
  select 1
  from threadmind.screenshot_submissions submission
  where submission.account_id = insight.account_id
    and submission.id = insight.submission_id
);

alter table threadmind.insight_bundles
  alter column generation_key set not null,
  add constraint insight_bundles_generation_key_nonempty check (length(btrim(generation_key)) > 0),
  add constraint insight_bundles_submission_owner_fk
    foreign key (account_id, submission_id)
    references threadmind.screenshot_submissions (account_id, id)
    on delete cascade;

create unique index insight_bundles_account_generation_key_uidx
  on threadmind.insight_bundles (account_id, generation_key);

create index insight_bundles_submission_generated_idx
  on threadmind.insight_bundles (account_id, submission_id, generated_at desc, id desc);
