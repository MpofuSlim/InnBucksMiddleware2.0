-- KYC intake (slice 12): a customer's request for a higher tier, the rounds
-- it goes through, and the photos each round shows the reviewer.
--
-- NOTHING in these tables is plaintext-sensitive. The typed national ID, the
-- typed address and every image are AES-256-GCM envelopes ("v1:" key id
-- prefix, random nonce, AAD binding each value to its row and column — see
-- KycCipher), sealed with keys from a key FILE that never sits in .env and
-- never rides a backup. The national ID is also kept as an HMAC under the
-- existing NATIONAL_ID_HMAC_KEY: the duplicate-search key.
--
-- Decisions are NOT made here. A later change adds the review step; this one
-- only stores what the customer sent, and the decision columns it will fill.

CREATE TABLE kyc_submission (
    id                  UUID                     PRIMARY KEY,
    customer_id         UUID                     NOT NULL REFERENCES customer (id),
    target_tier         VARCHAR(20)              NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    -- The round the submission is on: 1, then +1 each time a rejection
    -- reopens it for the files in its redo list.
    current_round       INTEGER                  NOT NULL,
    -- Enhanced only. AES-GCM envelope (text form), never plaintext.
    address_ciphertext  TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- When the customer last submitted a round / when one was last decided.
    submitted_at        TIMESTAMP WITH TIME ZONE,
    decided_at          TIMESTAMP WITH TIME ZONE,
    -- Set exactly while the CUSTOMER owes work on an open round: a draft
    -- (created_at + draft TTL) or a rejection that reopened the next round
    -- (decided_at + draft TTL). The lifecycle job expires what passes it.
    expires_at          TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_kyc_submission_target CHECK (target_tier IN ('standard', 'enhanced')),
    CONSTRAINT chk_kyc_submission_status
        CHECK (status IN ('draft', 'pending_review', 'approved', 'rejected', 'expired')),
    CONSTRAINT chk_kyc_submission_round CHECK (current_round >= 1),
    -- An address exactly when the target needs one.
    CONSTRAINT chk_kyc_submission_address
        CHECK ((target_tier = 'enhanced') = (address_ciphertext IS NOT NULL)),
    -- A deadline only where the customer can still act.
    CONSTRAINT chk_kyc_submission_expiry
        CHECK (expires_at IS NULL OR status IN ('draft', 'rejected'))
);

-- ONE open submission per customer. "Open" is a draft, a pending review, or a
-- rejection whose next round was reopened for redo (status rejected with a
-- deadline): the customer resumes THAT one instead of starting a second.
-- The same predicate is JdbcKycStore.OPEN_PREDICATE — keep them identical.
CREATE UNIQUE INDEX uq_kyc_submission_one_open ON kyc_submission (customer_id)
    WHERE status IN ('draft', 'pending_review') OR (status = 'rejected' AND expires_at IS NOT NULL);

CREATE INDEX idx_kyc_submission_customer ON kyc_submission (customer_id, created_at DESC);
CREATE INDEX idx_kyc_submission_expires_at ON kyc_submission (expires_at) WHERE expires_at IS NOT NULL;

-- One row per attempt. A round is FROZEN when the customer submits it: its ID
-- and its evidence never change afterwards, so a decision is always about
-- exactly what was submitted.
CREATE TABLE kyc_round (
    submission_id           UUID                     NOT NULL REFERENCES kyc_submission (id),
    round                   INTEGER                  NOT NULL,
    opened_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    frozen_at               TIMESTAMP WITH TIME ZONE,
    -- AES-GCM envelope of the CANONICAL number (upper-case, no separators),
    -- bound to (submission, round).
    national_id_ciphertext  TEXT                     NOT NULL,
    -- HMAC-SHA256 (NationalIdHasher) of the canonical number.
    national_id_hmac        VARCHAR(64)              NOT NULL,
    -- First 2 + last 3 characters, for display (only the last 3 for a short number).
    masked_id               VARCHAR(32)              NOT NULL,
    -- Against the sign-up hash (taken over the number AS TYPED): match | no_match | none_given.
    signup_match            VARCHAR(16)              NOT NULL,
    -- OTHER customers holding the same number (sign-up hash in any common
    -- spelling, or the HMAC of a KYC round they SUBMITTED — a draft never
    -- sent is no claim to it). Computed at freeze. For the reviewer only: a
    -- duplicate is never refused or shown to the customer.
    duplicate_count         INTEGER,
    -- Filled by the review step (a later change).
    decision                VARCHAR(16),
    decided_at              TIMESTAMP WITH TIME ZONE,
    -- Comma-separated codes (KycRejectionReason / KycFileKind); NULL unless rejected.
    rejection_reasons       VARCHAR(255),
    redo                    VARCHAR(255),
    -- When a rejected round's files were deleted at the end of their retention.
    files_purged_at         TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (submission_id, round),
    CONSTRAINT chk_kyc_round_round CHECK (round >= 1),
    CONSTRAINT chk_kyc_round_signup_match CHECK (signup_match IN ('match', 'no_match', 'none_given')),
    CONSTRAINT chk_kyc_round_duplicates CHECK (duplicate_count IS NULL OR duplicate_count >= 0),
    CONSTRAINT chk_kyc_round_decision CHECK (decision IS NULL OR decision IN ('approved', 'rejected')),
    -- Only a frozen round can be decided.
    CONSTRAINT chk_kyc_round_decided_frozen CHECK (decision IS NULL OR frozen_at IS NOT NULL)
);

-- Duplicate search: other customers' KYC rounds with the same number.
CREATE INDEX idx_kyc_round_national_id_hmac ON kyc_round (national_id_hmac);

-- ...and other customers who gave the same number at sign-up. The sign-up
-- column had no index; duplicate counting probes it with the hashes of every
-- common spelling of the number.
CREATE INDEX idx_customer_national_id_hash ON customer (national_id_hash)
    WHERE national_id_hash IS NOT NULL;

-- The photos. A file belongs to the round it was UPLOADED in; a later round
-- shows it again by reference (kyc_round_evidence), never by copying bytes.
-- Behind the KycFileStore interface: an object-store implementation would
-- keep this row and move only content_ciphertext out.
CREATE TABLE kyc_file (
    id                  UUID                     PRIMARY KEY,
    submission_id       UUID                     NOT NULL,
    round               INTEGER                  NOT NULL,
    kind                VARCHAR(24)              NOT NULL,
    -- AES-GCM envelope (binary form) of the RE-ENCODED JPEG, bound to
    -- (submission, upload round, kind, id) — KycAad.file.
    content_ciphertext  BYTEA                    NOT NULL,
    width               INTEGER                  NOT NULL,
    height              INTEGER                  NOT NULL,
    -- Size of the stored (plaintext) JPEG.
    byte_size           INTEGER                  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (submission_id, round) REFERENCES kyc_round (submission_id, round),
    CONSTRAINT chk_kyc_file_kind CHECK (kind IN ('id_front', 'id_back', 'selfie', 'proof_of_address')),
    CONSTRAINT chk_kyc_file_dimensions CHECK (width > 0 AND height > 0 AND byte_size > 0),
    -- The target of kyc_round_evidence's composite foreign key: a round can
    -- only show a file of its OWN submission, as the kind it was uploaded as.
    CONSTRAINT uq_kyc_file_owner UNIQUE (id, submission_id, kind)
);

CREATE INDEX idx_kyc_file_submission ON kyc_file (submission_id, round);

-- Ciphertext does not compress: skip TOAST's compression attempt.
ALTER TABLE kyc_file ALTER COLUMN content_ciphertext SET STORAGE EXTERNAL;

-- What each round SHOWS: per kind, the file the reviewer of that round sees.
-- Uploads write the open round's rows; a rejection with redo copies the
-- non-redo rows into the next round (same file ids). Files are deleted only
-- when no row here references them, which is what keeps an approved round's
-- evidence — carried-forward files included — out of reach of every purge.
--
-- The foreign key to the file is COMPOSITE: a row can only point at a file of
-- its own submission uploaded as the same kind, never at another customer's
-- photo or at a selfie labelled id_front. The file's seal (KycAad.file) binds
-- the same three values, so a writer who drops this constraint still gets a
-- file that does not open.
CREATE TABLE kyc_round_evidence (
    submission_id  UUID         NOT NULL,
    round          INTEGER      NOT NULL,
    kind           VARCHAR(24)  NOT NULL,
    file_id        UUID         NOT NULL,
    PRIMARY KEY (submission_id, round, kind),
    FOREIGN KEY (submission_id, round) REFERENCES kyc_round (submission_id, round),
    CONSTRAINT fk_kyc_round_evidence_file
        FOREIGN KEY (file_id, submission_id, kind) REFERENCES kyc_file (id, submission_id, kind),
    CONSTRAINT chk_kyc_round_evidence_kind
        CHECK (kind IN ('id_front', 'id_back', 'selfie', 'proof_of_address'))
);

CREATE INDEX idx_kyc_round_evidence_file ON kyc_round_evidence (file_id);
