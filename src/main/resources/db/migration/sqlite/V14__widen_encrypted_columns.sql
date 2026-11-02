-- Encrypted values now carry the id of the key that wrote them ("kp1:<id>:"),
-- which adds a few characters to every value. SQLite does not enforce VARCHAR
-- lengths, so there is nothing to widen - this migration exists to keep the two
-- vendors on the same version numbers.
SELECT 1;
