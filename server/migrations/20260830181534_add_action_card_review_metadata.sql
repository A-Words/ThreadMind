alter table threadmind.action_cards
  add column field_confidence jsonb not null default '{}'::jsonb,
  add column validation_issues jsonb not null default '[]'::jsonb,
  add constraint action_cards_field_confidence_object
    check (jsonb_typeof(field_confidence) = 'object'),
  add constraint action_cards_validation_issues_array
    check (jsonb_typeof(validation_issues) = 'array');
