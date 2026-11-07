-- Read access for applications: scoped tokens an app presents to fetch the
-- current value of specific keys, so a rotated key reaches it without being
-- copied by hand.

-- The server-wide switch. One row; off until a super admin turns it on.
CREATE TABLE api_access_setting (
    id               INTEGER      PRIMARY KEY,
    enabled          BOOLEAN      NOT NULL DEFAULT 0,
    updated_by_email VARCHAR(255),
    updated_at       TIMESTAMP
);
INSERT INTO api_access_setting (id, enabled) VALUES (1, 0);

-- A token belongs to a group, like an integration. Only the SHA-256 of the
-- token is stored; the prefix is its first characters, for telling tokens apart.
CREATE TABLE app_access_token (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id        INTEGER       NOT NULL REFERENCES user_group (id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    token_prefix    VARCHAR(12)  NOT NULL,
    created_by_id   INTEGER,
    created_by_name VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    last_used_at    TIMESTAMP
);
CREATE UNIQUE INDEX idx_app_access_token_hash ON app_access_token (token_hash);
CREATE INDEX idx_app_access_token_group ON app_access_token (group_id);

-- One key a token may read. key_id follows the key's rotations forward, so the
-- grant's own id is the stable reference an app is configured with.
CREATE TABLE app_access_grant (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    token_id         INTEGER    NOT NULL REFERENCES app_access_token (id) ON DELETE CASCADE,
    key_id           INTEGER    NOT NULL,
    last_read_key_id INTEGER,
    last_read_at     TIMESTAMP
);
CREATE INDEX idx_app_access_grant_token ON app_access_grant (token_id);
CREATE INDEX idx_app_access_grant_key ON app_access_grant (key_id);
