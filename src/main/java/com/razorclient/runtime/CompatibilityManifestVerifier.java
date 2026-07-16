package com.razorclient.runtime;

/** Contract for signed adapter-approval manifests; no remote code is loaded through this API. */
public interface CompatibilityManifestVerifier<TFingerprint, TManifest> {
    TManifest verifyEnvelope(byte[] envelope);
    TManifest parseCanonicalPayload(byte[] payload);
    boolean isExpired(TManifest manifest);
    boolean matchesFingerprint(TManifest manifest, TFingerprint fingerprint);
}
