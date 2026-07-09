-- Seed reference data and the structurizr-sequence pipeline configuration (dynamic-view/sequence
-- diagrams pulled from each product's Structurizr workspace, see structurizr-sequence-extract.md).
-- Which module runs each stage is defined in code — ru.beeline.staging.pipeline.PipelineDefinitions —
-- this row only carries scheduling/activation.

INSERT INTO staging.data_types (code, description)
VALUES ('structurizr-sequence', 'Sequence (dynamic view) diagrams exported from per-product Structurizr workspaces');

INSERT INTO staging.source_systems (code, name)
VALUES ('product-service', 'FDM Product Service (product/info + per-product Structurizr workspaces)');

INSERT INTO staging.configurations (code, artifact_type, data_type_id, source_system_id,
                                    schedule_interval_seconds, is_active)
VALUES ('structurizr-sequence-product-service',
        'structurizr-sequence',
        (SELECT id FROM staging.data_types WHERE code = 'structurizr-sequence'),
        (SELECT id FROM staging.source_systems WHERE code = 'product-service'),
        21600,
        TRUE);
