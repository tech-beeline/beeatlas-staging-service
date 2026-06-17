-- Operations need a stable external identifier (e.g. Sparx/dashboard method uid) to be
-- deduplicated across loads, the same way interfaces.uid and bi_steps.uid already work.

ALTER TABLE staging.operations ADD COLUMN ext_uid VARCHAR(50) UNIQUE;
