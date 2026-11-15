-- Idempotency keys are now stored as "<jwt subject>:<client key>" so each caller has its own key
-- space; widen the column to fit a subject (up to 200 chars) plus the 100-char client key.
ALTER TABLE idempotency_keys ALTER COLUMN key TYPE VARCHAR(301);
