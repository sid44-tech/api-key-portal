-- Encrypted values now carry the id of the key that wrote them ("kp1:<id>:"),
-- which adds a few characters to every value. Room for it, with margin: these
-- hold base64 ciphertext of pasted credentials, which can be long.
ALTER TABLE api_key ALTER COLUMN key_value TYPE VARCHAR(8192);
ALTER TABLE sso_configuration ALTER COLUMN client_secret TYPE VARCHAR(8192);
