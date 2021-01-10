CREATE TABLE api_key (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    service_name VARCHAR(100)  NOT NULL,
    key_value    VARCHAR(2048) NOT NULL,
    created_on   DATE          NOT NULL,
    expires_on   DATE,
    added_at     TIMESTAMP     NOT NULL
);

CREATE INDEX idx_api_key_service_name ON api_key (service_name);
