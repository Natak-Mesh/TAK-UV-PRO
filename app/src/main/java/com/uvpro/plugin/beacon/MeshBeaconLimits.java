package com.uvpro.plugin.beacon;

import android.content.Context;

import com.uvpro.plugin.ui.SettingsFragment;

/**
 * Runtime mesh beacon rate floors when MeshCore transmit is active and mesh limiting
 * is not disabled. Stored user prefs are unchanged; caps apply only at TX decision time.
 */
public final class MeshBeaconLimits {

    public static final int MIN_INTERVAL_SEC = 1800;
    public static final int MIN_SLOW_RATE_SEC = 1800;
    public static final int MIN_FAST_RATE_SEC = 300;
    public static final int MIN_TURN_TIME_SEC = 300;

    private MeshBeaconLimits() {}

    /** Mesh limits apply when MeshCore TX is on and admin has not disabled limiting. */
    public static boolean isActive(Context context) {
        if (context == null) {
            return false;
        }
        return SettingsFragment.isMeshTransmitEnabled(context)
                && !SettingsFragment.isDisableMeshBeaconLimiting(context);
    }

    public static int capIntervalSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_INTERVAL_SEC);
    }

    public static int capSlowRateSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_SLOW_RATE_SEC);
    }

    public static int capFastRateSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_FAST_RATE_SEC);
    }

    public static int capMinTurnTimeSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_TURN_TIME_SEC);
    }
}
