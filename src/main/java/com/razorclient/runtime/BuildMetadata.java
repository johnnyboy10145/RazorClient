package com.razorclient.runtime;

/** Immutable build identity shared by diagnostics without exposing mutable build state. */
public final class BuildMetadata {
    private final String launcherHash;
    private final String bootstrapHash;
    private final String agentHash;
    private final String obfuscatorVersion;
    private final String adapterVersion;
    private final String schemaVersion;

    public BuildMetadata(String launcherHash, String bootstrapHash, String agentHash,
                         String obfuscatorVersion, String adapterVersion, String schemaVersion) {
        this.launcherHash = value(launcherHash);
        this.bootstrapHash = value(bootstrapHash);
        this.agentHash = value(agentHash);
        this.obfuscatorVersion = value(obfuscatorVersion);
        this.adapterVersion = value(adapterVersion);
        this.schemaVersion = value(schemaVersion);
    }

    public String getLauncherHash() { return launcherHash; }
    public String getBootstrapHash() { return bootstrapHash; }
    public String getAgentHash() { return agentHash; }
    public String getObfuscatorVersion() { return obfuscatorVersion; }
    public String getAdapterVersion() { return adapterVersion; }
    public String getSchemaVersion() { return schemaVersion; }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
