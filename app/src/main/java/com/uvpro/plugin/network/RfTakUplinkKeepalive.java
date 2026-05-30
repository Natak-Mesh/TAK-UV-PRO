package com.uvpro.plugin.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.uvpro.plugin.contacts.ContactReachability;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.cot.CotBuilder;

import java.util.List;

/**
 * Re-dispatches known RF peer SA to the TAK/Wi‑Fi network so serverless LAN peers
 * (e.g. Wi‑Fi-only J15) see radio-only contacts without waiting for the next RF beacon.
 */
public final class RfTakUplinkKeepalive {

    private static final String TAG = "UVPro.RfTakUplink";
    private static final long INTERVAL_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 5_000L;
    private static final long SA_STALE_MS = 120_000L;

    private final MapView mapView;
    private final CotBridge cotBridge;
    private final Handler handler;
    private final Runnable tick;
    private boolean running;

    public RfTakUplinkKeepalive(MapView mapView, CotBridge cotBridge) {
        this.mapView = mapView;
        this.cotBridge = cotBridge;
        this.handler = new Handler(Looper.getMainLooper());
        this.tick = new Runnable() {
            @Override
            public void run() {
                onTick();
            }
        };
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        handler.postDelayed(tick, INITIAL_DELAY_MS);
        Log.i(TAG, "RF→TAK uplink keepalive started (interval=" + (INTERVAL_MS / 1000) + "s)");
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** Run one sweep immediately (e.g. after radio connect). */
    public void kick() {
        if (mapView != null) {
            mapView.post(this::tickOnce);
        }
    }

    private void onTick() {
        if (!running) {
            return;
        }
        handler.postDelayed(tick, INTERVAL_MS);
        tickOnce();
    }

    private void tickOnce() {
        try {
            if (mapView == null || cotBridge == null) {
                return;
            }
            if (!cotBridge.isRfToTakUplinkActive()) {
                return;
            }
            if (!ContactReachability.isLocalWifiAvailable()) {
                return;
            }
            if (!cotBridge.isRadioConnected()) {
                return;
            }
            int sent = relayKnownRfContactsToTak();
            if (sent > 0) {
                Log.d(TAG, "RF→TAK uplink keepalive sent " + sent + " peer SA(s)");
            }
        } catch (Exception e) {
            Log.w(TAG, "keepalive tick failed", e);
        }
    }

    private int relayKnownRfContactsToTak() {
        Contacts contacts = Contacts.getInstance();
        if (contacts == null) {
            return 0;
        }
        List<Contact> all = contacts.getAllContacts();
        if (all == null || all.isEmpty()) {
            return 0;
        }
        String selfUid = safeUid(MapView.getDeviceUid());
        int sent = 0;
        for (Contact contact : all) {
            if (!(contact instanceof IndividualContact)) {
                continue;
            }
            IndividualContact ic = (IndividualContact) contact;
            String uid = safeUid(ic.getUID());
            if (uid.isEmpty() || uid.equalsIgnoreCase(selfUid)) {
                continue;
            }
            if (!cotBridge.isRfNativeContact(uid)
                    && !cotBridge.isRfHeardCallsign(ic.getName())) {
                continue;
            }
            CotEvent sa = buildPeerSaFromMap(ic, uid);
            if (sa != null && sa.isValid() && cotBridge.dispatchRfPeerSaToTakNetwork(sa)) {
                sent++;
            }
        }
        return sent;
    }

    private CotEvent buildPeerSaFromMap(IndividualContact contact, String uid) {
        MapItem item = contact.getMapItem();
        if (item == null && mapView != null && mapView.getRootGroup() != null) {
            item = mapView.getRootGroup().deepFindUID(uid);
        }
        if (!(item instanceof PointMapItem)) {
            return null;
        }
        GeoPoint gp = ((PointMapItem) item).getPoint();
        if (gp == null || !gp.isValid()) {
            return null;
        }
        String callsign = contact.getName() != null ? contact.getName().trim() : uid;
        double speed = 0.0;
        double course = 0.0;
        try {
            speed = Double.parseDouble(item.getMetaString("Speed", "0"));
        } catch (Exception ignored) {
        }
        try {
            course = Double.parseDouble(item.getMetaString("course", "0"));
        } catch (Exception ignored) {
        }
        String team = item.getMetaString("team", "Cyan");
        if (team == null || team.trim().isEmpty()) {
            team = "Cyan";
        }
        return CotBuilder.buildPositionCot(
                callsign,
                gp.getLatitude(),
                gp.getLongitude(),
                gp.getAltitude(),
                (float) speed,
                (float) course,
                team.trim(),
                SA_STALE_MS,
                null,
                null,
                null,
                null,
                uid);
    }

    private static String safeUid(String uid) {
        return uid != null ? uid.trim() : "";
    }
}
