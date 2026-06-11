package com.uvpro.plugin.bluetooth;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies supported BTECH Classic Bluetooth radios during discovery and pairing.
 *
 * <p>Android reports only three broadcast names: {@code UV-PRO}, {@code UV-50}, and
 * {@code VR-N76}. Because the name does not distinguish multiple units of the same model,
 * {@link #resolveRadioId} prefers the cached Ident ID ({@link UvProRadioIdentCache},
 * read from the radio after connect) when known; otherwise the last three MAC octets
 * (e.g. {@code UV-PRO (A1B2C3)}).
 */
public final class UvProBtDeviceMatcher {

    public static final int DOT_AVAILABLE = 0xFF00CC44;
    public static final int DOT_PAIRED = 0xFFFF4444;
    /** Bonded / remembered radio not seen advertising in the current scan. */
    public static final int DOT_UNAVAILABLE = 0xFF888888;
    public static final int DOT_UNKNOWN = 0xFF888888;

    private static final ConcurrentHashMap<String, String> pickerModelByMac =
            new ConcurrentHashMap<>();

    /** Legacy / variant names still accepted for bonded-device enumeration. */
    public static final String[] NAME_PATTERNS = {
            "UV-PRO", "BTECH", "GMRS-PRO", "UV-50PRO", "UVPRO",
            "UV-50X", "UV50", "PRO50", "VR-N76", "VRN76", "BT-TNC", "TNC"
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

    public static boolean isSupportedRadioModel(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        // Android sometimes truncates the advert to "UV-P" before the full "UV-PRO" arrives.
        if (upper.startsWith("UV-P") && !upper.contains("UV-50") && !upper.contains("UV50")) {
            return true;
        }
        return upper.contains("UV-PRO") || upper.contains("UVPRO")
                || upper.contains("UV-50") || upper.contains("UV50")
                || upper.contains("VR-N76") || upper.contains("VRN76")
                || upper.startsWith("VR-N");
    }

    /** BTECH factory OUI seen on UV-PRO / UV-50 / VR-N76 Classic Bluetooth radios. */
    public static boolean isBtechRadioMac(@Nullable String macAddress) {
        String mac = normalizeMacAddress(macAddress);
        return mac != null && mac.startsWith("38:D2:00:");
    }

    /** Candidate during in-app scan — Android advertises UV-PRO, UV-50, or VR-N76 only. */
    public static boolean isDiscoveryCandidate(@Nullable BluetoothDevice device,
                                               @Nullable String advertName) {
        if (device == null) {
            return false;
        }
        String name = firstNonEmpty(advertName, safeDeviceName(device));
        if (isSupportedRadioModel(name) || matchesName(name)) {
            return true;
        }
        try {
            return isBtechRadioMac(device.getAddress());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isLikelyUvProDevice(@Nullable BluetoothDevice device) {
        return isDiscoveryCandidate(device, null);
    }

    /** Picker row: {@code UV-PRO (A1B2C3)}, {@code UV-50 (…)}, or {@code VR-N76 (…)}. */
    public static String formatPickerLabel(@Nullable BluetoothDevice device) {
        return resolveModelLabel(device) + " (" + resolveRadioId(device) + ")";
    }

    public static void clearPickerModelCache() {
        pickerModelByMac.clear();
    }

    /** Locks picker model from the first advert/bonded name so truncation (e.g. VR-N) cannot relabel. */
    public static void cachePickerModelLabel(@Nullable String macAddress,
                                             @Nullable String advertOrBondedName) {
        String mac = normalizeMacAddress(macAddress);
        if (mac == null) {
            return;
        }
        String label = resolveModelLabelFromName(advertOrBondedName);
        if (label != null) {
            pickerModelByMac.put(mac, label);
        }
    }

    public static String resolveModelLabel(@Nullable BluetoothDevice device) {
        if (device != null) {
            String mac = normalizeMacAddress(device.getAddress());
            if (mac != null) {
                String cached = pickerModelByMac.get(mac);
                if (cached != null) {
                    return cached;
                }
            }
        }
        return resolveModelLabelFromName(safeDeviceName(device));
    }

    private static String resolveModelLabelFromName(@Nullable String name) {
        if (name != null) {
            String upper = name.toUpperCase(Locale.US);
            if (upper.contains("VR-N76") || upper.contains("VRN76") || upper.startsWith("VR-N")) {
                return "VR-N76";
            }
            if (upper.contains("UV-50") || upper.contains("UV50")) {
                return "UV-50";
            }
        }
        return "UV-PRO";
    }

    /**
     * Display ID: cached Ident Information when known (post-connect), else last three
     * MAC octets as six uppercase hex digits.
     */
    public static String resolveRadioId(@Nullable BluetoothDevice device) {
        if (device == null) {
            return "?";
        }
        String ident = UvProRadioIdentCache.get(device.getAddress());
        if (ident != null && !ident.isEmpty()) {
            return ident;
        }
        try {
            return radioIdFromAddress(device.getAddress());
        } catch (Exception ignored) {
            return "?";
        }
    }

    public static String radioIdFromAddress(@Nullable String macAddress) {
        if (macAddress == null || macAddress.trim().isEmpty()) {
            return "?";
        }
        String hex = macAddress.trim().toUpperCase(Locale.US).replace(":", "");
        if (hex.length() < 6) {
            return hex.isEmpty() ? "?" : hex;
        }
        return hex.substring(hex.length() - 6);
    }

    @Nullable
    public static String normalizeMacAddress(@Nullable String macAddress) {
        if (macAddress == null || macAddress.trim().isEmpty()) {
            return null;
        }
        return macAddress.trim().toUpperCase(Locale.US);
    }

    /** True when {@code radioId} matches the last-three-octet ID for {@code macAddress}. */
    public static boolean radioIdMatchesAddress(@Nullable String radioId,
                                                @Nullable String macAddress) {
        if (radioId == null || macAddress == null) {
            return false;
        }
        return radioIdFromAddress(macAddress).equalsIgnoreCase(radioId.trim());
    }

    public static int availabilityDotColor(int bondState, boolean seenInLiveDiscovery) {
        if (!seenInLiveDiscovery) {
            return DOT_UNAVAILABLE;
        }
        if (bondState == BluetoothDevice.BOND_BONDED) {
            return DOT_PAIRED;
        }
        if (bondState == BluetoothDevice.BOND_NONE) {
            return DOT_AVAILABLE;
        }
        return DOT_UNKNOWN;
    }

    @Nullable
    public static String safeDeviceName(@Nullable BluetoothDevice device) {
        if (device == null) {
            return null;
        }
        try {
            return device.getName();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static String firstNonEmpty(@Nullable String a, @Nullable String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }
}
