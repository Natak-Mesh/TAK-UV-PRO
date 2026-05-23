package com.uvpro.plugin.location;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.location.framework.Location;
import com.atakmap.android.location.framework.LocationManager;
import com.atakmap.android.location.framework.LocationProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MetaDataHolder2;
import com.atakmap.coremap.conversions.ConversionFactors;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.radio.UVProRadioControlManager;

/**
 * Reads GPS from the UV-PRO over Bluetooth and injects it into ATAK as plugin-supplied GPS.
 */
public final class RadioGpsBridge {

    private static final String TAG = "UVPro.RadioGps";

    public static final String PREF_AUGMENT_GPS_FROM_RADIO = "uvpro_augment_gps_from_radio";
    public static final long AUGMENT_INTERVAL_MS = 120_000L;
    public static final String MOCK_SOURCE_LABEL = "UV-PRO GPS";
    private static final String INTERNAL_GPS_PROVIDER_UID = "internal-gps-chip";

    private RadioGpsBridge() {
    }

    public static final class UpdateResult {
        public final boolean success;
        public final String message;

        public UpdateResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * Parse the 18-byte position block returned after {@code GET_POSITION} status byte.
     */
    public static RadioPositionFix parsePositionPayload(byte[] payload) {
        if (payload == null || payload.length < 18) {
            return null;
        }
        int latRaw = readSigned24(payload, 0);
        int lonRaw = readSigned24(payload, 3);
        double latitude = latRaw / 60.0 / 500.0;
        double longitude = lonRaw / 60.0 / 500.0;
        int altitude = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        int speedKnots = ((payload[8] & 0xFF) << 8) | (payload[9] & 0xFF);
        int heading = ((payload[10] & 0xFF) << 8) | (payload[11] & 0xFF);
        long utcSeconds = ((payload[12] & 0xFFL) << 24)
                | ((payload[13] & 0xFFL) << 16)
                | ((payload[14] & 0xFFL) << 8)
                | (payload[15] & 0xFFL);
        int accuracy = ((payload[16] & 0xFF) << 8) | (payload[17] & 0xFF);
        double speedMs = speedKnots * ConversionFactors.KNOTS_TO_METERS_PER_S;
        return new RadioPositionFix(latitude, longitude, altitude, speedMs, heading,
                accuracy, utcSeconds);
    }

    /**
     * True when the phone's internal GPS chip is reporting a valid fix to ATAK.
     */
    public static boolean isPhoneGpsAvailable(MapView mapView) {
        try {
            LocationProvider internal = LocationManager.getInstance()
                    .getLocationProvider(INTERNAL_GPS_PROVIDER_UID);
            if (internal == null || !internal.getEnabled()) {
                return false;
            }
            Location loc = internal.getLastReportedLocation();
            if (loc == null || !loc.isValid()) {
                return false;
            }
            GeoPoint point = loc.getPoint();
            return point != null && point.isValid()
                    && RadioPositionFix.isValidCoordinate(point.getLatitude(), point.getLongitude());
        } catch (Exception e) {
            Log.w(TAG, "Could not evaluate phone GPS state", e);
            return false;
        }
    }

    public static UpdateResult updateGpsFromRadio(BtConnectionManager btManager,
                                                    UVProRadioControlManager radioControlManager,
                                                    MapView mapView) {
        if (btManager == null || !btManager.isConnected()) {
            return new UpdateResult(false, "Connect to the radio first.");
        }
        if (radioControlManager == null) {
            return new UpdateResult(false, "Radio control unavailable.");
        }
        if (mapView == null) {
            return new UpdateResult(false, "Map unavailable.");
        }
        try {
            RadioPositionFix fix = radioControlManager.readRadioPosition();
            if (fix == null) {
                return new UpdateResult(false, "Radio did not return a GPS fix.");
            }
            if (!fix.isValid()) {
                return new UpdateResult(false, "Radio GPS fix is invalid or not locked.");
            }
            if (!injectIntoAtak(mapView, fix)) {
                return new UpdateResult(false, "Could not apply radio GPS to ATAK.");
            }
            return new UpdateResult(true,
                    String.format("Updated ATAK from radio GPS: %.5f, %.5f",
                            fix.latitude, fix.longitude));
        } catch (Exception e) {
            Log.e(TAG, "updateGpsFromRadio failed", e);
            return new UpdateResult(false, "Radio GPS update failed: " + e.getMessage());
        }
    }

    public static boolean injectIntoAtak(MapView mapView, RadioPositionFix fix) {
        if (mapView == null || fix == null || !fix.isValid()) {
            return false;
        }
        MetaDataHolder2 data = mapView.getMapData();
        if (data == null) {
            return false;
        }
        GeoPoint gp = new GeoPoint(fix.latitude, fix.longitude, fix.altitudeMeters);
        data.setMetaString("locationSourcePrefix", "mock");
        data.setMetaBoolean("mockLocationAvailable", true);
        data.setMetaString("mockLocationSource", MOCK_SOURCE_LABEL);
        data.setMetaString("mockLocationSourceColor", "#FF00BCD4");
        data.setMetaBoolean("mockLocationCallsignValid", true);
        data.setMetaString("mockLocation", gp.toString());
        data.setMetaLong("mockLocationTime", SystemClock.elapsedRealtime());
        data.setMetaLong("mockGPSTime", new CoordinatedTime().getMilliseconds());
        data.setMetaInteger("mockFixQuality", fix.accuracyMeters > 0 ? 1 : 2);
        if (!Double.isNaN(fix.speedMetersPerSecond)) {
            data.setMetaDouble("mockLocationSpeed", fix.speedMetersPerSecond);
        }
        if (!Double.isNaN(fix.courseDegrees)) {
            data.setMetaDouble("mockLocationBearing", fix.courseDegrees);
        }
        if (fix.accuracyMeters > 0) {
            data.setMetaDouble("mockLocationAccuracy", fix.accuracyMeters);
        }
        Intent gpsReceived = new Intent("com.atakmap.android.map.WR_GPS_RECEIVED");
        AtakBroadcast.getInstance().sendBroadcast(gpsReceived);
        Log.i(TAG, "Injected radio GPS into ATAK: " + gp);
        return true;
    }

    private static int readSigned24(byte[] payload, int offset) {
        int raw = ((payload[offset] & 0xFF) << 16)
                | ((payload[offset + 1] & 0xFF) << 8)
                | (payload[offset + 2] & 0xFF);
        if ((raw & 0x00800000) != 0) {
            raw |= 0xFF000000;
        } else {
            raw &= 0x00FFFFFF;
        }
        return raw;
    }
}
