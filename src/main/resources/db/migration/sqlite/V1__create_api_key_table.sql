-- SQLite was introduced after schema version 4, so this creates the api_key table
-- in its final (V4) shape directly. Version numbers stay aligned with the
-- postgresql migrations; V2-V4 intentionally don't exist for SQLite.
CREATE TABLE api_key (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             VARCHAR(100)  NOT NULL,
    service_name     VARCHAR(100)  NOT NULL,
    key_value        VARCHAR(2048) NOT NULL,
    created_on       DATE          NOT NULL,
    expires_on       DATE,
    notes            VARCHAR(1000),
    added_at         TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,
    last_revealed_at TIMESTAMP
);

CREATE INDEX idx_api_key_service_name ON api_key (service_name);
