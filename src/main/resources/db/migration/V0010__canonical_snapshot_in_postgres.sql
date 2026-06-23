-- Canonical snapshot JSON (built by Transformer, consumed by Saver) is large and must
-- never travel through Camunda process variables — TEXT_ there is character varying(4000)
-- and overflows. Store it alongside raw_content instead; Camunda only carries rawDataRefId.
ALTER TABLE staging.raw_data_refs
    ADD COLUMN canonical_snapshot_json TEXT;
