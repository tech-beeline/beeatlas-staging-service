-- staging.sequences had no business key to match a Structurizr dynamicView (its `key`, e.g. "UC01")
-- across loads — only tc_id/tc_code. Needed by SequenceMatchService (structurizr-sequence saver) to
-- find-or-create the identity row the same way BiStepMatchService/InterfaceMatchService/OperationMatchService
-- match by uid/ext_uid.

ALTER TABLE staging.sequences ADD COLUMN key VARCHAR(255);

CREATE UNIQUE INDEX idx_sequences_tc_id_key ON staging.sequences (tc_id, key);
