package com.uvpro.plugin.location;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.radio.UVProRadioControlManager;

/**
 * Periodically pulls GPS from the radio when augment mode is enabled and the phone has no fix.
 */
public final class RadioGpsAugmentController {

    private static final String TAG = "UVPro.RadioGps";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            runAugmentTick();
            if (augmentEnabled) {
                handler.postDelayed(this, RadioGpsBridge.AUGMENT_INTERVAL_MS);
            }
        }
    };

    private BtConnectionManager btManager;
    private UVProRadioControlManager radioControlManager;
    private MapView mapView;
    private volatile boolean augmentEnabled;

    public void install(BtConnectionManager btManager,
                        UVProRadioControlManager radioControlManager,
                        MapView mapView) {
        this.btManager = btManager;
        this.radioControlManager = radioControlManager;
        this.mapView = mapView;
    }

    public void setAugmentEnabled(boolean enabled) {
        if (augmentEnabled == enabled) {
            return;
        }
        augmentEnabled = enabled;
        handler.removeCallbacks(tick);
        if (enabled) {
            handler.postDelayed(tick, RadioGpsBridge.AUGMENT_INTERVAL_MS);
            Log.i(TAG, "Radio GPS augment enabled (2 min when phone has no fix)");
        } else {
            Log.i(TAG, "Radio GPS augment disabled");
        }
    }

    public boolean isAugmentEnabled() {
        return augmentEnabled;
    }

    public void onRadioConnected() {
        if (augmentEnabled) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, RadioGpsBridge.AUGMENT_INTERVAL_MS);
        }
    }

    public void onRadioDisconnected() {
        handler.removeCallbacks(tick);
    }

    public void shutdown() {
        handler.removeCallbacks(tick);
        augmentEnabled = false;
    }

    public RadioGpsBridge.UpdateResult manualUpdate() {
        return RadioGpsBridge.updateGpsFromRadio(btManager, radioControlManager, mapView);
    }

    private void runAugmentTick() {
        if (!augmentEnabled || btManager == null || !btManager.isConnected()) {
            return;
        }
        if (RadioGpsBridge.isPhoneGpsAvailable(mapView)) {
            Log.d(TAG, "Augment tick skipped (phone GPS available)");
            return;
        }
        new Thread(() -> {
            RadioGpsBridge.UpdateResult result =
                    RadioGpsBridge.updateGpsFromRadio(btManager, radioControlManager, mapView);
            if (result.success) {
                Log.i(TAG, "Augment GPS: " + result.message);
            } else {
                Log.d(TAG, "Augment GPS skipped/failed: " + result.message);
            }
        }, "uvpro-radio-gps-augment").start();
    }
}
