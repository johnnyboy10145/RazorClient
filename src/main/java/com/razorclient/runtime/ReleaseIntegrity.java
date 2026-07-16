package com.razorclient.runtime;

import java.nio.file.Path;

/** Runtime-facing integrity contract implemented by the native release boundary. */
public interface ReleaseIntegrity {
    boolean verifyLauncherSignature();
    boolean verifyEmbeddedPayloads();
    boolean verifyExtractedPayload(Path path, String expectedHash);
    boolean verifyReleaseMetadata(BuildMetadata metadata);
}
