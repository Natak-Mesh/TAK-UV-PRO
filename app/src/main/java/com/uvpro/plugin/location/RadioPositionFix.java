package com.uvpro.plugin.location;

/**
 * Parsed GPS fix from the UV-PRO radio ({@code GET_POSITION} response).
 */
public final class RadioPositionFix {

    public final double latitude;
    public final double longitude;
    public final double altitudeMeters;
    public final double speedMetersPerSecond;
    public final double courseDegrees;
    public final int accuracyMeters;
    public final long utcTimeSeconds;

    public RadioPositionFix(double latitude, double longitude, double altitudeMeters,
                            double speedMetersPerSecond, double courseDegrees,
                            int accuracyMeters, long utcTimeSeconds) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.courseDegrees = courseDegrees;
        this.accuracyMeters = accuracyMeters;
        this.utcTimeSeconds = utcTimeSeconds;
    }

    public boolean isValid() {
        return isValidCoordinate(latitude, longitude);
    }

    public static boolean isValidCoordinate(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return false;
        }
        return !(Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001);
    }
}
