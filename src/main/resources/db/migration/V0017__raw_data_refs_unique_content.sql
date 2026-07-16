-- FR-08-01 / documentation/visions/staging/ARCHITECTURE.md §3.4: if the latest raw content for an
-- artifact hasn't changed, only updated_at is refreshed on the existing row — no new row is created.
-- SparxE2EAdapter used to implement this as a find-then-insert-or-update, which is not atomic and
-- raced under concurrent/overlapping pipeline runs for the same artifact, producing duplicate rows
-- with an identical content_hash. The adapter now does a single INSERT ... ON CONFLICT DO UPDATE,
-- relying on this constraint to make the "unchanged content" case atomic.
--
-- Apply only after any pre-existing duplicate raw_data_refs rows (and FK references to them from
-- canonical-snapshot / notice / product / container tables) have been cleaned up on the target
-- environment — a UNIQUE constraint cannot be created while violating rows exist.

ALTER TABLE staging.raw_data_refs
    ADD CONSTRAINT uq_raw_data_refs_artifact_hash UNIQUE (artifact_uid, artifact_type, content_hash);
