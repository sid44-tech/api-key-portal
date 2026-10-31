-- Multi-user support: accounts belong to a group (a team, company or family).
-- Keys and integrations gain an owner and a group so they can be scoped.

CREATE TABLE user_group (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX idx_user_group_name ON user_group (lower(name));

CREATE TABLE user_account (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    -- BCrypt hash; the plaintext password is never stored
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    group_id      INTEGER      REFERENCES user_group (id),
    job_title     VARCHAR(100),
    enabled       BOOLEAN      NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    last_login_at TIMESTAMP
);

-- Email is the login identifier, matched case-insensitively
CREATE UNIQUE INDEX idx_user_account_email ON user_account (lower(email));
CREATE INDEX idx_user_account_group ON user_account (group_id);

-- Nullable so existing rows survive the migration; the bootstrap admin claims
-- anything still unowned on the next start.
ALTER TABLE api_key ADD COLUMN owner_id INTEGER REFERENCES user_account (id);
ALTER TABLE api_key ADD COLUMN group_id INTEGER REFERENCES user_group (id);

CREATE INDEX idx_api_key_owner ON api_key (owner_id);
CREATE INDEX idx_api_key_group ON api_key (group_id);

ALTER TABLE service_integration ADD COLUMN group_id INTEGER REFERENCES user_group (id);

CREATE INDEX idx_service_integration_group ON service_integration (group_id);
