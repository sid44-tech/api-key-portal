CREATE TABLE service_integration (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    provider         VARCHAR(50)   NOT NULL,
    name             VARCHAR(100)  NOT NULL,
    tenant_id        VARCHAR(100)  NOT NULL,
    client_id        VARCHAR(100)  NOT NULL,
    client_secret    VARCHAR(2048) NOT NULL,
    added_at         TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,
    last_verified_at TIMESTAMP
);
