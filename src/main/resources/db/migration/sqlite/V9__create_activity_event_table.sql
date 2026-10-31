-- History of who created (and removed) what, for the admin-only activity page.
--
-- Deliberately free of foreign keys: an audit row has to outlive the thing it
-- describes. The actor, group and target are flat snapshots so a record still
-- reads correctly after the key is deleted, the integration removed or the
-- person renamed. Nothing secret is stored here - names and ids only.

CREATE TABLE activity_event (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at TIMESTAMP    NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    actor_id    INTEGER,
    actor_name  VARCHAR(100) NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    group_id    INTEGER,
    group_name  VARCHAR(100),
    target_id   INTEGER,
    target_name VARCHAR(300) NOT NULL,
    -- MANUAL, PROVISIONED or GUIDED; set on key creations only
    key_source  VARCHAR(20),
    detail      VARCHAR(300)
);

-- The page reads newest-first, either for one group or across all of them
CREATE INDEX idx_activity_event_occurred ON activity_event (occurred_at DESC);
CREATE INDEX idx_activity_event_group ON activity_event (group_id, occurred_at DESC);
