-- Store raw artifact payload directly in PostgreSQL (replaces S3/MinIO).
-- s3_bucket / s3_key are kept nullable for any existing rows; new rows won't use them.
ALTER TABLE staging.raw_data_refs
    ALTER COLUMN s3_bucket DROP NOT NULL,
    ALTER COLUMN s3_key    DROP NOT NULL;

ALTER TABLE staging.raw_data_refs
    ADD COLUMN raw_content TEXT;
