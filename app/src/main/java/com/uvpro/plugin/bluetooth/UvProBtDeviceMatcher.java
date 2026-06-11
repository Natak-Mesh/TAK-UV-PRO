package com.uvpro.plugin.bluetooth;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Identifies BTECH UV-PRO family radios during Classic Bluetooth discovery.
 * Preserved for a future scan/connect rebuild; not wired to live connection logic.
 */
public final class UvProBtDeviceMatcher {

    public static final String[] NAME_PATTERNS = {
            "UV-PRO", "BTECH", "GMRS-PRO", "UV-50PRO", "UVPRO",
            "UV-50X", "UV50", "PRO50", "VR-N76", "BT-TNC", "TNC"
    };

    private UvProBtDeviceMatcher() {
    }

    public static boolean matchesName(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        for (String pattern : NAME_PATTERNS) {
            if (upper.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLikelyUvProDevice(@Nullable BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        try {
            String name = device.getName();
            return name != null && !name.trim().isEmpty() && matchesName(name);
        } catch (Exception ignored) {
            return false;
        }
    }
}
