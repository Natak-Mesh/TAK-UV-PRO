package com.uvpro.plugin.protocol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.protocol.RfTxArbitrator;
import com.uvpro.plugin.ui.SettingsFragment;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Schedules ping replies using deterministic callsign slots ({@link NetSlotConfig}).
 */
public final class PingReplyScheduler {

    private static final String TAG = "UVPro.PingReply";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CotBridge cotBridge;
    private Runnable pendingReply;

    public PingReplyScheduler(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
    }

    /**
     * Queue a position reply after the configured slot delay for this device.
     */
    public void scheduleReply(Context context) {
        if (context == null || cotBridge == null) {
            return;
        }
        if (!SettingsFragment.isPingReplyEnabled(context)) {
            return;
        }
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        String callsign = SettingsFragment.getCallsign(context);
        int slotCount = NetSlotConfig.getSlotCount(context);
        int rawSlot = NetSlotConfig.computeSlotIndex(callsign, slotCount);
        // Remove "slot 0" as a transmit option: effective slots are 1..N.
        int effectiveSlot = rawSlot + 1;
        long slotTimeMs = Math.max(1L, Math.round(NetSlotConfig.getSlotTimeSec(context) * 1000.0f));
        long baseDelayMs = effectiveSlot * slotTimeMs;
        long jitterWindowMs = Math.max(1L, Math.round(slotTimeMs * 0.10));
        long jitterMs = ThreadLocalRandom.current().nextLong(-jitterWindowMs, jitterWindowMs + 1L);
        long delayMs = Math.max(0L, baseDelayMs + jitterMs);
        cancelPending();
        RfTxArbitrator.get().setPingReplyPending(true);
        final Context appCtx = context.getApplicationContext();
        pendingReply = () -> transmitReply(appCtx);
        handler.postDelayed(pendingReply, delayMs);
        Log.d(TAG, "Ping reply scheduled in " + delayMs + "ms (slot " + effectiveSlot
                + "/" + slotCount
                + ", rawSlot=" + rawSlot
                + ", base=" + baseDelayMs + "ms, jitter=" + jitterMs + "ms)");
    }

    public void cancelPending() {
        if (pendingReply != null) {
            handler.removeCallbacks(pendingReply);
            pendingReply = null;
        }
        RfTxArbitrator.get().setPingReplyPending(false);
    }

    private void transmitReply(Context context) {
        pendingReply = null;
        RfTxArbitrator.get().setPingReplyPending(false);
        if (!SettingsFragment.isPingReplyEnabled(context)) {
            return;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return;
            }
            PointMapItem self = mv.getSelfMarker();
            if (self == null) {
                return;
            }
            com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();
            double speedMs = 0.0;
            double course = 0.0;
            try {
                speedMs = Double.parseDouble(self.getMetaString("Speed", "0"));
            } catch (Exception ignored) {
            }
            try {
                course = Double.parseDouble(self.getMetaString("course", "0"));
            } catch (Exception ignored) {
            }
            cotBridge.sendPositionOverRadio(
                    gp.getLatitude(), gp.getLongitude(),
                    gp.getAltitude(), (float) speedMs, (float) course, -1);
            Log.d(TAG, "Ping reply sent (slotted)");
            PingReplyNotifier.notifyPingReplySent(context);
        } catch (Exception e) {
            Log.w(TAG, "Ping reply transmit failed: " + e.getMessage());
        }
    }
}
