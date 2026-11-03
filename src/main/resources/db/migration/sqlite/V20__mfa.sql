-- Two-factor authentication for password accounts: a TOTP secret (RFC 6238,
-- the kind every authenticator app speaks) and single-use recovery codes.
--
-- The secret is encrypted at rest with the portal's key, like a stored
-- credential: anyone who reads it can mint valid codes. The last time step a
-- code was accepted for is kept so a code can never be replayed inside its
-- window. Accounts that sign in through the identity provider never use any
-- of this - their second factor is the provider's business.
ALTER TABLE user_account ADD COLUMN mfa_secret VARCHAR(8192);
ALTER TABLE user_account ADD COLUMN mfa_enabled_at TIMESTAMP;
ALTER TABLE user_account ADD COLUMN mfa_last_used_step BIGINT;

-- Only the SHA-256 of each recovery code is kept, so the one showing at
-- enrollment is the only copy; a used code is deleted rather than marked.
CREATE TABLE mfa_recovery_code (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL
);

CREATE INDEX idx_mfa_recovery_code_user ON mfa_recovery_code (user_id);
CREATE UNIQUE INDEX idx_mfa_recovery_code_hash ON mfa_recovery_code (code_hash);
