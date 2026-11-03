-- A chat channel a group posts its alerts to, alongside the emails: keys about
-- to expire, automatic replacements that failed, and sign-in lockouts. One per
-- group per provider (Microsoft Teams, Slack), so a group can have both. The
-- webhook URL lets anyone holding it post to the channel, so it is encrypted at
-- rest like a provider credential.
CREATE TABLE alert_channel (
    id                        INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id                  INTEGER          NOT NULL REFERENCES user_group (id),
    provider                  VARCHAR(20)   NOT NULL,
    name                      VARCHAR(60)   NOT NULL,
    webhook_url               VARCHAR(8192) NOT NULL,
    enabled                   BOOLEAN       NOT NULL DEFAULT 1,
    notify_expiries           BOOLEAN       NOT NULL DEFAULT 1,
    notify_rotation_failures  BOOLEAN       NOT NULL DEFAULT 1,
    notify_lockouts           BOOLEAN       NOT NULL DEFAULT 0,
    -- how the last post went, so the page can say "working" or "failing since"
    last_sent_at              TIMESTAMP,
    last_error                VARCHAR(500),
    created_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX idx_alert_channel_group_provider ON alert_channel (group_id, provider);
