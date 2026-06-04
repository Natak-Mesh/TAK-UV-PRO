package com.uvpro.plugin.protocol;

/**
 * Identifies which RF link delivered an inbound AX.25 frame (UV-PRO radio vs MeshCore).
 */
public enum RfInboundTransport {
    UVPRO,
    MESHCORE
}
