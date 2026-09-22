ALTER TABLE staging.operation_versions
    ADD COLUMN IF NOT EXISTS connection_operation_id integer;
