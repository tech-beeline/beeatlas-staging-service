package ru.beeline.staging.pipeline.transformer;

/**
 * Maps a source's raw payload into whatever entity-graph shape this module's paired
 * saver expects. Selected per configuration by moduleCode. The return value is any
 * JSON-serializable object — TransformerWorker serializes it generically and stores it
 * in raw_data_refs.canonical_snapshot_json without knowing its shape; the concrete type
 * is a private agreement between this transformer and its paired ArtifactSaver (e.g.
 * E2ESequenceTransformer <-> E2ECanonicalSaver share E2ESequenceSnapshot).
 */
public interface ArtifactTransformer {

    String moduleCode();

    Object transform(String artifactUid, String rawContent) throws Exception;
}
