-- The last automatic replacement (or provider-side deletion) that failed for a
-- key, kept on the key itself so the dashboard can list what needs a human.
-- Until now a refusal lived only in the audit log, which no page can query.
-- Both are cleared by the next success; NULL means nothing is wrong, or the key
-- is not one the portal rotates.
ALTER TABLE api_key ADD COLUMN rotation_failed_at TIMESTAMP;
ALTER TABLE api_key ADD COLUMN rotation_failure VARCHAR(500);
