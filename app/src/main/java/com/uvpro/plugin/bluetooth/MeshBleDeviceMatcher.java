package com.uvpro.plugin.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.ParcelUuid;

import androidx.annotation.Nullable;

import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;

import java.util.Locale;
import java.util.UUID;

/**
 * Identifies MeshCore companion radios during BLE discovery.
 */
public final class MeshBleDeviceMatcher {

    public static final UUID UUID_UART_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID UUID_UART_RX =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID UUID_UART_TX =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID UUID_CCC =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    public static final int DEFAULT_PAIRING_PIN = 123456;
    public static final String NAME_PREFIX = "MeshCore-";

    private MeshBleDeviceMatcher() {
    }

    public static boolean advertisesMeshService(@Nullable ScanResult result) {
        if (result == null) return false;
        ScanRecord record = result.getScanRecord();
        if (record == null || record.getServiceUuids() == null) return false;
        for (ParcelUuid parcelUuid : record.getServiceUuids()) {
            if (parcelUuid == null || parcelUuid.getUuid() == null) continue;
            UUID uuid = parcelUuid.getUuid();
            if (UUID_UART_SERVICE.equals(uuid)) return true;
        }
        return false;
    }

    /**
     * Name-based fallback for chipsets/Android versions that don't surface the service UUID in
     * the parsed scan record. MeshCore firmware always prefixes the BLE name with
     * {@code "MeshCore-"} ({@code BLE_NAME_PREFIX} in companion_radio/MyMesh.h), so we only match
     * that. Generic hardware-brand keywords were removed because they matched unrelated BLE gear
     * and produced false positives. Real nodes with custom firmware are still caught by the
     * primary service-UUID filter.
     */
    public static boolean matchesMeshName(@Nullable String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.regionMatches(true, 0, NAME_PREFIX, 0, NAME_PREFIX.length())) return true;
        return trimmed.toLowerCase(Locale.US).contains("meshcore");
    }

    @Nullable
    public static String resolveName(@Nullable ScanResult result, @Nullable BluetoothDevice device) {
        ScanRecord record = result != null ? result.getScanRecord() : null;
        String name = record != null ? record.getDeviceName() : null;
        if (name == null || name.trim().isEmpty()) {
            try {
                name = device != null ? device.getName() : null;
            } catch (Exception ignored) {
                name = null;
            }
        }
        return name;
    }

    public static boolean isMeshDevice(@Nullable Context context,
                                       @Nullable ScanResult result,
                                       @Nullable BluetoothDevice device,
                                       boolean trustHardwareFilter) {
        if (device == null) return false;
        if (UvProBtDeviceMatcher.isLikelyUvProDevice(device)) return false;
        if (context != null && isKnownMeshAddress(context, device.getAddress())) return true;
        if (trustHardwareFilter || advertisesMeshService(result)) return true;
        return matchesMeshName(resolveName(result, device));
    }

    public static boolean isMeshDevice(@Nullable Context context,
                                       @Nullable ScanResult result,
                                       @Nullable BluetoothDevice device) {
        return isMeshDevice(context, result, device, false);
    }

    public static boolean isMeshDevice(@Nullable Context context,
                                       @Nullable BluetoothDevice device) {
        return isMeshDevice(context, null, device, false);
    }

    public static boolean isKnownMeshAddress(@Nullable Context context,
                                             @Nullable String address) {
        if (context == null || address == null || address.trim().isEmpty()) return false;
        String meshTarget = BluetoothDeviceRegistry.getMeshConnectTargetAddress(context);
        return meshTarget != null
                && meshTarget.equalsIgnoreCase(
                        BluetoothDeviceRegistry.normalizeAddress(address));
    }

    public static boolean isKnownMeshRecord(@Nullable BtDeviceRecord record) {
        return record != null;
    }

    public static String pairingHintMessage(@Nullable BluetoothDevice device) {
        String label = "MeshCore";
        if (device != null) {
            try {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty()) {
                    label = name.trim();
                } else if (device.getAddress() != null) {
                    label = device.getAddress();
                }
            } catch (Exception ignored) {
            }
        }
        return "Pairing " + label + ". If Android asks for a PIN, enter the code shown on the "
                + "node's screen, or " + DEFAULT_PAIRING_PIN + " if it has no screen.";
    }

    /** Short PIN guidance for the device picker (shown before the system pairing dialog appears). */
    public static String pinGuidance() {
        return "PIN: shown on node screen, or " + DEFAULT_PAIRING_PIN + " if no screen";
    }
}
