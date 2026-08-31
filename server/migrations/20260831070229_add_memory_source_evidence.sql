alter table threadmind.memory_records
  add column source_evidence jsonb not null default '[]'::jsonb,
  add constraint memory_records_source_evidence_array
    check (jsonb_typeof(source_evidence) = 'array');
