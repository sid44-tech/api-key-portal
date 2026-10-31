-- Invitations replace open sign-up: an admin invites an email address, and the
-- link in that email is the only way to create an account.

-- The seeded super admin starts on a default password and has to replace it
-- before they can use anything else. Existing accounts chose their own already.
ALTER TABLE user_account ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT 0;

CREATE TABLE user_invitation (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         VARCHAR(255) NOT NULL,
    -- SHA-256 of the token, hex encoded. The token itself only exists in the
    -- emailed link, so a copy of this table doesn't let anyone accept an invite.
    token_hash    VARCHAR(64)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    group_id      INTEGER      REFERENCES user_group (id),
    invited_by_id INTEGER      REFERENCES user_account (id),
    created_at    TIMESTAMP    NOT NULL,
    expires_at    TIMESTAMP    NOT NULL,
    accepted_at   TIMESTAMP,
    revoked_at    TIMESTAMP
);

CREATE UNIQUE INDEX idx_user_invitation_token ON user_invitation (token_hash);
CREATE INDEX idx_user_invitation_email ON user_invitation (lower(email));
CREATE INDEX idx_user_invitation_group ON user_invitation (group_id);
