-- Payments reference accounts by id only (no FK): they belong to a different service boundary.
CREATE TABLE payments (
    id             UUID PRIMARY KEY,
    from_account   BIGINT      NOT NULL,
    to_account     BIGINT      NOT NULL,
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    currency       CHAR(3)     NOT NULL,
    reference      VARCHAR(140),
    status         VARCHAR(20) NOT NULL,
    hold_id        BIGINT,
    entry_id       BIGINT,
    failure_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    key             VARCHAR(100) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    payment_id      UUID,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    aggregate_id VARCHAR(64)  NOT NULL,
    type         VARCHAR(40)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
