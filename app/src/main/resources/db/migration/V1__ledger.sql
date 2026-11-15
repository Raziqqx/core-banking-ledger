-- Accounts hold a cached balance; the postings table is the source of truth.
CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    owner           VARCHAR(100) NOT NULL,
    currency        CHAR(3)      NOT NULL,
    balance         BIGINT       NOT NULL DEFAULT 0,   -- minor units
    held            BIGINT       NOT NULL DEFAULT 0,   -- reserved by active holds
    overdraft_limit BIGINT       NOT NULL DEFAULT 0,
    system          BOOLEAN      NOT NULL DEFAULT FALSE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT held_non_negative CHECK (held >= 0),
    CONSTRAINT overdraft_limit_non_negative CHECK (overdraft_limit >= 0),
    -- Defence in depth: the database itself refuses an overdraft beyond the limit.
    CONSTRAINT no_overdraft CHECK (system OR balance - held >= -overdraft_limit)
);
-- One funding (system) account per currency.
CREATE UNIQUE INDEX accounts_system_currency ON accounts (currency) WHERE system;

CREATE TABLE journal_entries (
    id          BIGSERIAL PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    reverses    BIGINT REFERENCES journal_entries (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- An entry can be reversed at most once.
CREATE UNIQUE INDEX journal_entries_reverses ON journal_entries (reverses) WHERE reverses IS NOT NULL;

CREATE TABLE postings (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT  NOT NULL REFERENCES journal_entries (id),
    account_id BIGINT  NOT NULL REFERENCES accounts (id),
    amount     BIGINT  NOT NULL CHECK (amount <> 0),
    currency   CHAR(3) NOT NULL
);
CREATE INDEX postings_account ON postings (account_id, id);
CREATE INDEX postings_entry ON postings (entry_id);

CREATE TABLE holds (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT      NOT NULL REFERENCES accounts (id),
    amount     BIGINT      NOT NULL CHECK (amount > 0),
    status     VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX holds_active ON holds (account_id) WHERE status = 'ACTIVE';
