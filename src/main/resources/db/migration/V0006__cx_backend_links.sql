-- Idempotency mapping between our canonical model and cx-backend's CJ library. cx-backend
-- has no spare field to carry our artifact_uid that's queryable via any filter, so we track
-- which CJ we already created for a given e2e-sequence artifact ourselves. BI/BiStep-level
-- dedup is handled by cx-backend itself (saveOrUpdateElements matches by BPMN element id when
-- re-importing into an already-bpmn-imported CJ), so no separate bi-step link table is needed.

CREATE TABLE staging.cx_backend_cj_links (
    artifact_uid VARCHAR(100) PRIMARY KEY,
    cj_id        BIGINT NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
