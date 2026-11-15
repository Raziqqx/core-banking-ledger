CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    event_id   UUID        NOT NULL UNIQUE,  -- de-duplicates at-least-once delivery
    payment_id UUID        NOT NULL,
    account_id BIGINT      NOT NULL,
    message    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notifications_account ON notifications (account_id, id);
