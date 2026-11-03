-- Branding alongside the colours: the portal's name, shown in the header, the
-- page titles and the sign-in page, and an uploaded logo that replaces the key
-- mark. NULL means the built-in name and mark. The logo is stored in the
-- database rather than on disk so a single volume - or none, for the all-in-one
-- image's database - carries everything.
ALTER TABLE theme_settings ADD COLUMN portal_name VARCHAR(60);
ALTER TABLE theme_settings ADD COLUMN logo BLOB;
ALTER TABLE theme_settings ADD COLUMN logo_content_type VARCHAR(100);
