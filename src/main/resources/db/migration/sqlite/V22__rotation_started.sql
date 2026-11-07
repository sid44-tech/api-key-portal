-- Set just before the portal asks a provider to rotate a key in place (GitLab),
-- cleared once the new value is stored. Still set afterwards means a rotation
-- may have happened at the provider without the portal keeping its result -
-- the next sweep uses it to look for, and recover, the token that took over.
ALTER TABLE api_key ADD COLUMN rotation_started_at TIMESTAMP;
