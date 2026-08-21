-- Supports PipelineRunTextSearchRepository.findOverlappingContexts: without this, the overlap
-- join filters byte_range contexts (can be 100k+ per raw_data_ref_id) with a sequential scan.
CREATE INDEX IF NOT EXISTS idx_raw_data_contexts_byte_range
    ON staging.raw_data_contexts (
        raw_data_ref_id,
        ((position #>> '{primary,value,start_offset}')::bigint),
        ((position #>> '{primary,value,end_offset}')::bigint)
    )
    WHERE position #>> '{primary,type}' = 'byte_range';
