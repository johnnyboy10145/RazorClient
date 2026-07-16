package com.razorclient.network;

/** Captured release behavior; it never changes after packet interception. */
public enum PacketReleasePolicy {
    IMMEDIATE,
    DEADLINE,
    EXPLICIT_FLUSH,
    FLUSH_THEN_PASS
}
