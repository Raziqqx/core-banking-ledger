-- Saga crash recovery (see DECISIONS.md). A hold carries the caller's reference (payment:<id>) and,
-- once captured, the journal entry it became, so a stale payment can be compensated even when the
-- saga died before persisting hold_id / entry_id on the payment row.
ALTER TABLE holds
    ADD COLUMN reference VARCHAR(100),
    ADD COLUMN entry_id  BIGINT REFERENCES journal_entries (id);
CREATE INDEX holds_reference ON holds (reference) WHERE reference IS NOT NULL;

-- The recovery job scans in-flight payments by age, and finds idempotency keys by payment.
CREATE INDEX payments_in_flight ON payments (updated_at) WHERE status IN ('PENDING', 'RESERVED', 'POSTED');
CREATE INDEX idempotency_keys_in_progress ON idempotency_keys (created_at) WHERE status = 'IN_PROGRESS';
CREATE INDEX idempotency_keys_payment ON idempotency_keys (payment_id);
