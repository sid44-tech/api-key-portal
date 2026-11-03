-- Google service account keys and AWS access keys never expire on their own, so
-- for those the portal makes the expiry date true itself: once a replacement has
-- been minted and the date arrives, the sweep deletes the old credential at the
-- provider and records when here. NULL means the credential has not been deleted
-- by the portal - either because that is not how its provider works, or not yet.
ALTER TABLE api_key ADD COLUMN revoked_at TIMESTAMP;
