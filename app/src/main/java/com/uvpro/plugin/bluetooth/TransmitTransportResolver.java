package com.uvpro.plugin.bluetooth;

/**
 * Picks the active RF transmit transport from user toggle preferences.
 * When the preferred transport is not connected, falls back to the other link if available.
 */
public final class TransmitTransportResolver {

    private TransmitTransportResolver() {
    }

    public static BtConnectionManager resolve(
            boolean meshTransmitEnabled,
            boolean uvproTransmitEnabled,
            BtConnectionManager meshManager,
            BtConnectionManager uvproManager) {
        BtConnectionManager preferred = null;
        if (meshTransmitEnabled && meshManager != null) {
            preferred = meshManager;
        } else if (uvproTransmitEnabled && uvproManager != null) {
            preferred = uvproManager;
        }
        if (preferred != null) {
            if (preferred.isConnected()) {
                return preferred;
            }
            BtConnectionManager alternate =
                    preferred == meshManager ? uvproManager : meshManager;
            if (alternate != null && alternate.isConnected()) {
                return alternate;
            }
            return preferred;
        }
        if (meshManager != null && meshManager.isConnected()) {
            return meshManager;
        }
        if (uvproManager != null && uvproManager.isConnected()) {
            return uvproManager;
        }
        return meshManager != null ? meshManager : uvproManager;
    }
}
