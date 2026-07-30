-- InnBucks Middleware 2.0 — clean greenfield baseline.
-- Consolidates the proven OradianMiddleware V1–V9 schema with the 2.0 changes
-- baked in from day one (no legacy warts to carry):
--   * customer.core_provider / core_external_id replace oradian_external_id —
--     the middleware is core-agnostic behind the CoreBankingPort.
--   * customer.pin_hash is nullable from the start (PIN is set via the
--     OTP-gated /auth/pin/set flow, never at registration).
--   * refresh_token.token_hash is NOT NULL from the start (opaque secrets,
--     only the SHA-256 lands in the DB — no pre-hash legacy rows exist).
--   * audit_event carries row_hmac + chain_hmac (tamper evidence, HMAC-keyed,
--     chained through audit_chain_head) — NOT NULL, no pre-chain legacy rows.
--   * otp_challenge.code_hash is a keyed HMAC-SHA256 (OtpHasher), never a
--     bare hash of the 6-digit space.
--   * idempotency_record supports claim-row locking (response_status = 0 is
--     the in-progress sentinel, response_body nullable while claimed).

CREATE TABLE customer (
    id                    UUID                     PRIMARY KEY,
    country               VARCHAR(2)               NOT NULL,
    msisdn                VARCHAR(20)              NOT NULL,
    -- NULL until the customer completes the OTP-gated PIN-setup flow.
    pin_hash              VARCHAR(255),
    kyc_tier              VARCHAR(20)              NOT NULL DEFAULT 'basic',
    -- Keyed HMAC-SHA256 (NationalIdHasher) — never a bare hash.
    national_id_hash      VARCHAR(255),
    -- Which core banking system holds the relationship (FINERACT / VEENGU /
    -- legacy ORADIAN) and the customer's stable externalId there. For
    -- Fineract the externalId is client-assigned = this row's id.
    core_provider         VARCHAR(16),
    core_external_id      VARCHAR(64),
    status                VARCHAR(20)              NOT NULL DEFAULT 'pending_verification',
    failed_pin_attempts   INTEGER                  NOT NULL DEFAULT 0,
    last_failed_pin_at    TIMESTAMP WITH TIME ZONE,
    locked_until          TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT customer_country_msisdn_unique UNIQUE (country, msisdn)
);

CREATE INDEX idx_customer_msisdn ON customer (msisdn);

CREATE UNIQUE INDEX idx_customer_core_external_id
    ON customer (core_provider, core_external_id)
    WHERE core_external_id IS NOT NULL;

CREATE TABLE refresh_token (
    jti                UUID                     PRIMARY KEY,
    customer_id        UUID                     NOT NULL REFERENCES customer (id),
    family_id          UUID                     NOT NULL,
    device_hash        VARCHAR(255),
    -- Only the SHA-256 of the opaque secret is stored — a DB read can never
    -- yield a usable refresh token.
    token_hash         VARCHAR(64)              NOT NULL,
    issued_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    replaced_by_jti    UUID,
    revoked_at         TIMESTAMP WITH TIME ZONE,
    revocation_reason  VARCHAR(64)
);

CREATE INDEX idx_refresh_token_customer ON refresh_token (customer_id);
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token (expires_at);
CREATE UNIQUE INDEX uq_refresh_token_token_hash ON refresh_token (token_hash);

-- Tamper-evident audit trail. row_hmac seals each row's content; chain_hmac
-- binds it to its predecessor (HMAC(key, prev_chain_hmac || row_hmac)), so a
-- deleted or reordered row breaks the link at the next survivor. Writes
-- serialise on audit_chain_head (single row, SELECT ... FOR UPDATE).
CREATE TABLE audit_event (
    id              BIGSERIAL                PRIMARY KEY,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    country         VARCHAR(2)               NOT NULL,
    customer_id     UUID,
    action          VARCHAR(64)              NOT NULL,
    outcome         VARCHAR(20)              NOT NULL,
    correlation_id  VARCHAR(64),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    device_hash     VARCHAR(255),
    metadata        JSONB,
    row_hmac        VARCHAR(64)              NOT NULL,
    chain_hmac      VARCHAR(64)              NOT NULL
);

CREATE INDEX idx_audit_event_customer ON audit_event (customer_id, occurred_at DESC);
CREATE INDEX idx_audit_event_action ON audit_event (action, occurred_at DESC);

CREATE TABLE audit_chain_head (
    id          SMALLINT                 PRIMARY KEY CHECK (id = 1),
    chain_hmac  VARCHAR(64),
    updated_at  TIMESTAMP WITH TIME ZONE
);

-- The chain's genesis: NULL chain_hmac = "no predecessor" (hashed as the
-- empty string). Exactly one row, ever.
INSERT INTO audit_chain_head (id, chain_hmac, updated_at) VALUES (1, NULL, NULL);

-- Idempotency-Key store for state-changing endpoints. The client supplies a
-- stable key per logical request; the FIRST caller claims the key with an
-- INSERT ... ON CONFLICT DO NOTHING row (response_status = 0, body NULL)
-- BEFORE executing, so concurrent retries can never double-execute the work.
-- Completed rows replay their cached response; same key + different body
-- rejects as 422 idempotency_conflict.
CREATE TABLE idempotency_record (
    idempotency_key       VARCHAR(64)              PRIMARY KEY,
    request_fingerprint   VARCHAR(64)              NOT NULL,
    http_method           VARCHAR(10)              NOT NULL,
    request_path          VARCHAR(255)             NOT NULL,
    -- 0 = claimed / in progress; > 0 = the stored HTTP status to replay.
    response_status       INTEGER                  NOT NULL,
    response_body         TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_record_expires_at
    ON idempotency_record (expires_at);

-- One-time-passcode challenges for PIN setup, PIN reset, and (future) step-up
-- auth. Only one active (non-consumed, non-expired) challenge per
-- (msisdn, purpose) at a time — issuing a new one supersedes any prior one.
-- code_hash is keyed HMAC-SHA256 (OtpHasher).
CREATE TABLE otp_challenge (
    id                UUID                     PRIMARY KEY,
    country           VARCHAR(2)               NOT NULL,
    msisdn            VARCHAR(20)              NOT NULL,
    purpose           VARCHAR(32)              NOT NULL,
    code_hash         VARCHAR(64)              NOT NULL,
    attempts          INTEGER                  NOT NULL DEFAULT 0,
    issued_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at       TIMESTAMP WITH TIME ZONE,
    superseded_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_otp_active
    ON otp_challenge (msisdn, purpose, expires_at DESC)
    WHERE consumed_at IS NULL AND superseded_at IS NULL;

CREATE INDEX idx_otp_expires_at ON otp_challenge (expires_at);

-- Single-use enforcement for PIN-setup/reset verification tokens: the first
-- successful consume of a token's jti lands a row here; a replay of the same
-- jti hits the primary-key conflict and is rejected.
CREATE TABLE consumed_verification_token (
    jti         UUID                     PRIMARY KEY,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_consumed_verification_token_expires_at ON consumed_verification_token (expires_at);
