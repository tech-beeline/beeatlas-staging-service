package ru.beeline.staging.pipeline;

/**
 * Strategy that maps a source's raw payload into the source-agnostic {@link CanonicalSnapshot}.
 * One implementation per artifactType; TransformerWorker dispatches to the matching bean
 * by {@link #supportedType()}. Adding a new source type never requires touching this
 * interface, TransformerWorker, or the Saver — only a new implementation.
 */
public interface ArtifactTransformer {

    String supportedType();

    CanonicalSnapshot transform(String artifactUid, byte[] rawBytes) throws Exception;
}
