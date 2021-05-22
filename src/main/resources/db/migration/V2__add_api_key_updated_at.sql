ALTER TABLE api_key ADD COLUMN updated_at TIMESTAMP;

UPDATE api_key SET updated_at = added_at;

ALTER TABLE api_key ALTER COLUMN updated_at SET NOT NULL;
