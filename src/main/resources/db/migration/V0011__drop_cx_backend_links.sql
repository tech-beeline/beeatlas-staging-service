-- cx-backend publishing was removed: the canonical model (bi_steps/interfaces/operations +
-- *_versions, artifact_batches) is our own representation and is no longer pushed to any
-- other microservice's data model.
DROP TABLE IF EXISTS staging.cx_backend_cj_links;
