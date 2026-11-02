-- A group-level switch on each integration. Off, and nobody in the group can
-- mint keys through it - without the admin having to delete it (and re-enter
-- the provider credential later) just to pause it.
ALTER TABLE service_integration ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT 1;
