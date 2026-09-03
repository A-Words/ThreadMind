alter table threadmind.action_receipts
  add column if not exists contact_context jsonb;

alter table threadmind.action_receipts
  drop constraint if exists action_receipts_contact_context_shape;

alter table threadmind.action_receipts
  add constraint action_receipts_contact_context_shape check (
    contact_context is null or (
      status = 'succeeded'
      and jsonb_typeof(contact_context) = 'object'
      and contact_context->>'source' = 'android_contacts_provider'
      and contact_context->>'permissionStatus' in ('granted', 'denied', 'not_required', 'unavailable')
      and jsonb_typeof(contact_context->'queries') = 'array'
      and jsonb_array_length(contact_context->'queries') <= 10
      and jsonb_typeof(contact_context->'records') = 'array'
      and jsonb_array_length(contact_context->'records') <= 10
    )
  );
