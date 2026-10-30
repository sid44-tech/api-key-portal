-- Google Cloud integrations store an encrypted RSA private key in client_secret,
-- which doesn't fit in the original 2048 characters.
ALTER TABLE service_integration ALTER COLUMN client_secret TYPE VARCHAR(8192);
