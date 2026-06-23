-- 1. Raw payload is now gzip-compressed before storage (saves space). Switching the column
--    to BYTEA. Existing rows (plain-text, uncompressed) cannot be gunzip'd by the new code path,
--    so they are invalidated here: content_hash is reset to a value that can never match a real
--    SHA-256 hex digest, forcing the next scan of that artifact to re-fetch and store it gzipped.
ALTER TABLE staging.raw_data_refs
    ALTER COLUMN raw_content TYPE BYTEA USING NULL;

UPDATE staging.raw_data_refs SET content_hash = 'invalidated-by-gzip-migration-v0012';

-- 2. Provenance fields on bi_step_versions: where this step came from in Sparx, for future
--    matching of the Sparx artifact to its Beeatlas counterpart.
ALTER TABLE staging.bi_step_versions
    ADD COLUMN external_guid VARCHAR(100),
    ADD COLUMN source_id     VARCHAR(100);
