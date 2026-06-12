package com.uvpro.plugin.protocol;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.ui.SettingsFragment;

/**
 * Access to live radio TX for settings and administration actions.
 */
public final class UVProRadioServices {

    private static final String TAG = "UVPro.RadioSvc";

    private static volatile BtConnectionManager btManager;
    private static volatile EncryptionManager encryptionManager;

    private UVProRadioServices() {
    }

    public static void install(BtConnectionManager bt, EncryptionManager encryption) {
        btManager = bt;
        encryptionManager = encryption;
    }

    public static void clear() {
        btManager = null;
        encryptionManager = null;
    }

    /** Apply encryption toggle + shared secret from ATAK SharedPreferences. */
    public static void syncEncryptionFromSettings(Context context) {
        EncryptionManager em = encryptionManager;
        if (em == null) {
            return;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return;
        }
        if (SettingsFragment.isEncryptionEnabled(ctx)) {
            em.setSharedSecret(SettingsFragment.getEncryptionPassphrase(ctx));
        } else {
            em.setSharedSecret(null);
        }
    }

    public static boolean isConnected() {
        BtConnectionManager bt = btManager;
        return bt != null && bt.isConnected();
    }

    /**
     * Broadcast current slot settings to the net (TYPE_NET_SLOT_CONFIG).
     */
    public static boolean distributeNetSlotConfig(Context context) {
        BtConnectionManager bt = btManager;
        if (bt == null || !bt.isConnected()) {
            Log.w(TAG, "Distribute net slots: not connected");
            return false;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return false;
        }
        int slotCount = NetSlotConfig.getSlotCount(ctx);
        float slotTimeSec = NetSlotConfig.getSlotTimeSec(ctx);
        String issuer = SettingsFragment.getCallsign(ctx);
        int sequence = (int) (System.currentTimeMillis() / 1000L);
        byte[] payload = NetSlotConfig.encodePayload(slotCount, slotTimeSec, sequence, issuer);
        UVProPacket packet = new UVProPacket(UVProPacket.TYPE_NET_SLOT_CONFIG, payload);
        return transmitPacket(packet);
    }

    private static boolean transmitPacket(UVProPacket packet) {
        try {
            BtConnectionManager bt = btManager;
            if (bt == null) {
                return false;
            }
            byte[] packetBytes = packet.encode();
            EncryptionManager em = encryptionManager;
            if (em != null && em.isEnabled()) {
                packetBytes = em.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encrypt failed for net slot config");
                    return false;
                }
            }
            String callsign = SettingsFragment.getCallsign(
                    MapView.getMapView() != null
                            ? MapView.getMapView().getContext()
                            : null);
            Ax25Frame frame = Ax25Frame.createUVProFrame(callsign, 0, packetBytes);
            boolean ok = bt.sendKissFrame(frame.encode());
            if (ok) {
                Log.i(TAG, "Net slot config transmitted");
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "transmitPacket failed", e);
            return false;
        }
    }

    private static Context resolveContext(Context context) {
        if (context != null) {
            return context;
        }
        MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : null;
    }
}
