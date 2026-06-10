package com.uvpro.plugin.protocol;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.ui.SettingsFragment;
import com.uvpro.plugin.util.CallsignUtil;

/**
 * Transmits directed position-request pings (TYPE_PING with target callsign) over UV-PRO / MeshCore RF.
 */
public final class PositionRequester {

    private static final String TAG = "UVPro.PositionReq";

    private static volatile BtConnectionManager uvproTransport;
    private static volatile BtConnectionManager meshTransport;
    private static volatile EncryptionManager encryptionManager;

    private PositionRequester() {
    }

    public static void install(BtConnectionManager uvproBt, BtConnectionManager meshBt,
                               EncryptionManager encryption) {
        uvproTransport = uvproBt;
        meshTransport = meshBt;
        encryptionManager = encryption;
    }

    public static void clear() {
        uvproTransport = null;
        meshTransport = null;
        encryptionManager = null;
    }

    public static boolean requestPosition(Context context, String targetCallsign) {
        BtConnectionManager tx = resolveActiveTransmitManager(context);
        if (tx == null || !tx.isConnected()) {
            Log.w(TAG, "Request position: not connected");
            return false;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return false;
        }
        String sender = SettingsFragment.getCallsign(ctx);
        String targetRadio = CallsignUtil.toRadioCallsign(
                targetCallsign != null ? targetCallsign.trim() : "");
        if (targetRadio.isEmpty()) {
            return false;
        }
        try {
            UVProPacket packet = UVProPacket.createDirectedPingPacket(sender, targetRadio);
            byte[] packetBytes = packet.encode();
            EncryptionManager em = encryptionManager;
            if (em != null && em.isEnabled()) {
                packetBytes = em.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encrypt failed for directed ping");
                    return false;
                }
            }
            Ax25Frame frame = Ax25Frame.createUVProFrame(sender, 0, packetBytes);
            boolean ok = tx.sendKissFrame(frame.encode());
            if (ok) {
                String transportLabel = tx == meshTransport ? "MeshCore" : "UV-PRO";
                PingReplyNotifier.noteDirectedPingSent(ctx, targetRadio, transportLabel);
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "requestPosition failed", e);
            return false;
        }
    }

    private static BtConnectionManager resolveActiveTransmitManager(Context context) {
        Context ctx = resolveContext(context);
        boolean meshPreferred = ctx != null && SettingsFragment.isMeshTransmitEnabled(ctx);
        BtConnectionManager preferred = meshPreferred && meshTransport != null
                ? meshTransport
                : uvproTransport;
        BtConnectionManager alternate = preferred == meshTransport ? uvproTransport : meshTransport;
        if (preferred != null && preferred.isConnected()) {
            return preferred;
        }
        if (alternate != null && alternate.isConnected()) {
            return alternate;
        }
        return preferred != null ? preferred : alternate;
    }

    private static Context resolveContext(Context context) {
        if (context != null) {
            return context;
        }
        MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : null;
    }
}
