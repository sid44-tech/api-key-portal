-- Whether, once a key minted through this integration has been replaced and
-- has expired, the portal deletes the old credential at the provider. On by
-- default: for Google and AWS that is what has always happened and what makes
-- "expired" true there; for Azure it tidies the expired secret out of the app
-- registration. Off, and the old credential is left where it is.
ALTER TABLE service_integration ADD COLUMN delete_replaced_keys BOOLEAN NOT NULL DEFAULT 1;

-- The provider's id for this specific credential, where the resource id is the
-- thing it hangs off: for Azure, the password credential's keyId on the app
-- registration. What the deletion step deletes by. Null for keys minted before
-- this was recorded, which fall back to matching the expired secret by name.
ALTER TABLE api_key ADD COLUMN provider_credential_id VARCHAR(255);
