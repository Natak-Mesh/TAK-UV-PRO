package com.uvpro.plugin.bluetooth;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of per-radio Ident IDs ({@code PttReleaseIdInfo} from BSS settings).
 * Populated after connect via GAIA — not available during Bluetooth discovery.
 */
public final class UvProRadioIdentCache {

    private static final int BSS_IDENT_OFFSET = 8;
    private static final int BSS_IDENT_LEN = 12;

    private static final ConcurrentHashMap<String, String> byMac = new ConcurrentHashMap<>();

    private UvProRadioIdentCache() {
    }

    @Nullable
    public static String get(@Nullable String macAddress) {
        String mac = UvProBtDeviceMatcher.normalizeMacAddress(macAddress);
        if (mac == null) {
            return null;
        }
        return byMac.get(mac);
    }

    public static void put(@Nullable String macAddress, @Nullable String identId) {
        String mac = UvProBtDeviceMatcher.normalizeMacAddress(macAddress);
        if (mac == null) {
            return;
        }
        String normalized = normalizeIdent(identId);
        if (normalized == null) {
            byMac.remove(mac);
        } else {
            byMac.put(mac, normalized);
        }
    }

    /** Parses Identification Information from a READ_BSS_SETTINGS reply payload. */
    @Nullable
    public static String parseBssIdent(@Nullable byte[] bssPayload) {
        if (bssPayload == null || bssPayload.length < BSS_IDENT_OFFSET + BSS_IDENT_LEN) {
            return null;
        }
        String ascii = new String(
                bssPayload,
                BSS_IDENT_OFFSET,
                BSS_IDENT_LEN,
                StandardCharsets.US_ASCII);
        return normalizeIdent(ascii);
    }

    @Nullable
    private static String normalizeIdent(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.replace('\0', ' ').trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length() && out.length() < BSS_IDENT_LEN; i++) {
            char c = trimmed.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                out.append(c);
            }
        }
        String id = out.toString().trim();
        return id.isEmpty() ? null : id.toUpperCase(Locale.US);
    }
}
