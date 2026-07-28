-- Seller letterhead fields. All nullable: existing tenants keep working and the
-- PDF simply omits whatever is absent.
ALTER TABLE tenant ADD COLUMN address VARCHAR(512);
ALTER TABLE tenant ADD COLUMN phone   VARCHAR(20);
ALTER TABLE tenant ADD COLUMN email   VARCHAR(255);
