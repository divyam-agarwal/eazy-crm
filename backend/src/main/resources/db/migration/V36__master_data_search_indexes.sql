-- F1a adds `q` substring search to the customer, product and price-list list endpoints, plus an
-- explicit default sort on each. Neither had any index behind it: `customer.business_name`,
-- `product.name`, `product.sku` and `price_list.name` are all unindexed today, so the default
-- ORDER BY would sort a whole tenant in memory and a leading-wildcard ILIKE would seq-scan.
--
-- House pattern, stated in V33's own comment: the slice adding the query adds the index.
--
-- Two kinds, because they serve different plans and neither substitutes for the other:
--   btree (tenant_id, <col>) -> the default ORDER BY, and prefix matches
--   GIN + gin_trgm_ops       -> ILIKE '%needle%', which btree cannot serve at all
--
-- pg_trgm is a new extension for this schema. Available on RDS (rds_superuser may create it) and
-- present in the Testcontainers postgres image.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Plain CREATE INDEX, not CONCURRENTLY: there is no live database anywhere in this project (no
-- dev, staging or prod environment -- docs/ROADMAP.md SS1.4). All 36 migrations to date, this one
-- included, run for the first time at the SP2 cutover against an empty database, where the
-- ACCESS EXCLUSIVE lock every plain CREATE INDEX takes is instantaneous -- CONCURRENTLY would
-- protect nothing that exists yet. It would also cost real safety: CONCURRENTLY requires
-- `flyway:executeInTransaction=false`, trading away this migration's atomicity (a mid-file
-- failure here would leave earlier indexes committed and the schema half-migrated, needing manual
-- repair) for a shorter write lock this cutover doesn't need. A future index over a table that is
-- already populated and live does not get this exemption -- that one needs the
-- executeInTransaction=false trade for real, per squawk.toml.
--
-- Sort support. tenant_id leads every index in this schema: RLS adds `tenant_id = ...` to every
-- query, so a lone column index cannot serve the composite predicate.
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_customer_business_name ON customer (tenant_id, business_name);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_product_name           ON product   (tenant_id, name);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_price_list_name        ON price_list (tenant_id, name);

-- Search support. lower(...) matches the LOWER(col) LIKE LOWER(...) predicate the Specifications
-- build; a trigram index on the raw column would not be used by that expression.
--
-- The needle side of that predicate is lowercased in the JVM with Locale.ROOT, not by Postgres --
-- they agree for the data this product handles, but Locale.ROOT and Postgres's lower() (itself
-- locale/collation-dependent) can diverge on a handful of Unicode cases, dotted-I/dotless-i among
-- them.
--
-- None of these five lead with tenant_id, unlike every other index in this schema -- GIN +
-- gin_trgm_ops cannot combine with a leading plain column the way btree does, not without the
-- btree_gin extension, which this migration deliberately does not introduce. RLS stays correct
-- regardless: the tenant predicate is still applied as a filter/recheck after the bitmap index
-- scan, so no cross-tenant row is ever returned. What the omission costs is scan efficiency at
-- volume (a scan that touches more of the trigram index than a tenant-scoped one would), which the
-- load baseline (docs/ROADMAP.md item 12) is the right place to decide on evidence.
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_customer_business_name_trgm ON customer   USING gin (lower(business_name) gin_trgm_ops);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_customer_gstin_trgm         ON customer   USING gin (lower(gstin) gin_trgm_ops);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_product_name_trgm           ON product    USING gin (lower(name) gin_trgm_ops);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_product_sku_trgm            ON product    USING gin (lower(sku) gin_trgm_ops);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX idx_price_list_name_trgm        ON price_list USING gin (lower(name) gin_trgm_ops);
