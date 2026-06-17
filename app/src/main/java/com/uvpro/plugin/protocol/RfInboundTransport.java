package com.uvpro.plugin.protocol;

/**
 * Identifies which link delivered an inbound ping (UV-PRO radio, MeshCore, or TAK/Wi‑Fi).
 */
public enum RfInboundTransport {
    UVPRO,
    MESHCORE,
    WIFI
}
