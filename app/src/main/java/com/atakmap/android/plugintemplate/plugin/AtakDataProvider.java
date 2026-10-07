package com.atakmap.android.plugintemplate.plugin;

import android.util.Log;
import android.util.Pair;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.routes.RouteNavigationManager;
import com.atakmap.android.routes.RouteNavigator;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Locale;

public class AtakDataProvider {

    private static final String TAG = "DragonData";
    private static final double EARTH_RADIUS_M = 6371000.0;

    // Manual target fallback (used if RouteNavigator has no active navigation)
    private double targetLat = Double.NaN;
    private double targetLon = Double.NaN;

    // Distance traveled tracking
    private double prevLat = Double.NaN;
    private double prevLon = Double.NaN;
    private double totalDistanceMeters = 0.0;

    public static class HudData {
        public final String line1;
        public final String line2;
        public final String line3;
        // Extra data for the plugin panel dashboard
        public final String distToWaypoint;
        public final String distTraveled;
        public final String elevation;

        public HudData(String line1, String line2, String line3,
                String distToWaypoint, String distTraveled, String elevation) {
            this.line1 = line1;
            this.line2 = line2;
            this.line3 = line3;
            this.distToWaypoint = distToWaypoint;
            this.distTraveled = distTraveled;
            this.elevation = elevation;
        }
    }

    /** Manually set a navigation target (fallback if ATAK routing is not active). */
    public void setTarget(double lat, double lon, String name) {
        this.targetLat = lat;
        this.targetLon = lon;
    }

    public void clearTarget() {
        this.targetLat = Double.NaN;
        this.targetLon = Double.NaN;
    }

    public boolean hasTarget() {
        return !Double.isNaN(targetLat) && !Double.isNaN(targetLon);
    }

    /** Reset the accumulated distance traveled counter. */
    public void resetDistanceTraveled() {
        totalDistanceMeters = 0.0;
        prevLat = Double.NaN;
        prevLon = Double.NaN;
    }

    public HudData getHudData() {
        try {
            MapView mapView = MapView.getMapView();
            if (mapView == null) return noData();

            Marker self = mapView.getSelfMarker();
            if (self == null) return noData();

            GeoPoint point = self.getPoint();
            if (point == null) return noData();

            double heading = self.getTrackHeading();
            double selfLat = point.getLatitude();
            double selfLon = point.getLongitude();

            // Accumulate distance traveled from consecutive GPS fixes
            updateDistanceTraveled(selfLat, selfLon);

            // Elevation from GPS altitude
            String elevation = formatElevation(point);

            // Find the active navigation waypoint (RouteNavigator first, then manual)
            GeoPoint wpPoint = findActiveWaypoint();

            String line1 = formatHeading(heading);
            String line2;
            String distToWaypoint;
            String line3;

            if (wpPoint != null) {
                double wpLat = wpPoint.getLatitude();
                double wpLon = wpPoint.getLongitude();
                line2 = formatBearingDelta(heading, selfLat, selfLon, wpLat, wpLon);
                double dist = computeHaversine(selfLat, selfLon, wpLat, wpLon);
                distToWaypoint = formatDistance(dist);
                // Line 3: distance to waypoint when nav is active
                line3 = distToWaypoint;
            } else {
                // No active waypoint: show "NAV --" on line 2
                line2 = "NAV --";
                distToWaypoint = "---";
                // Line 3: fall back to elevation
                line3 = elevation;
            }

            return new HudData(line1, line2, line3,
                    distToWaypoint, formatDistance(totalDistanceMeters), elevation);
        } catch (Exception e) {
            Log.e(TAG, "Error getting HUD data", e);
            return noData();
        }
    }

    /**
     * Find the current active navigation waypoint.
     * Primary: ATAK's RouteNavigator singleton (tracks active route navigation).
     * Fallback: manually set target via setTarget().
     */
    private GeoPoint findActiveWaypoint() {
        // Check ATAK's RouteNavigator for an active navigation session
        try {
            RouteNavigator nav = RouteNavigator.getInstance();
            if (nav != null && nav.isNavigating()) {
                RouteNavigationManager mgr = nav.getNavManager();
                if (mgr != null) {
                    Pair<Integer, PointMapItem> obj = mgr.getCurrentObjective();
                    if (obj != null && obj.second != null) {
                        GeoPoint pt = obj.second.getPoint();
                        if (pt != null) return pt;
                    }
                }
            }
        } catch (Exception ignored) {
            // RouteNavigator may not be initialized; fall through to manual target
        }

        // Fallback to manually set target coordinates
        if (!Double.isNaN(targetLat) && !Double.isNaN(targetLon)) {
            return new GeoPoint(targetLat, targetLon);
        }

        return null;
    }

    /**
     * Accumulate distance traveled. Only counts fixes that move 2m–100m from the
     * previous position to filter GPS jitter and teleport jumps.
     */
    private void updateDistanceTraveled(double lat, double lon) {
        if (!Double.isNaN(prevLat) && !Double.isNaN(prevLon)) {
            double dist = computeHaversine(prevLat, prevLon, lat, lon);
            if (dist > 2.0 && dist < 100.0) {
                totalDistanceMeters += dist;
            }
        }
        prevLat = lat;
        prevLon = lon;
    }

    /** Format GPS altitude. Returns "ALT ---" if altitude is unavailable. */
    private String formatElevation(GeoPoint point) {
        double alt = point.getAltitude();
        if (Double.isNaN(alt) || alt == GeoPoint.UNKNOWN) return "ALT ---";
        return String.format(Locale.US, "ALT %dm", (int) Math.round(alt));
    }

    /** Format a distance in meters. Under 1000m shows meters, 1000m+ shows km. */
    private String formatDistance(double meters) {
        if (meters < 1000.0) {
            return String.format(Locale.US, "%dm", (int) Math.round(meters));
        } else {
            return String.format(Locale.US, "%.1fkm", meters / 1000.0);
        }
    }

    private String formatHeading(double heading) {
        if (Double.isNaN(heading)) return "H ---";
        int deg = ((int) Math.round(heading)) % 360;
        if (deg < 0) deg += 360;
        return String.format(Locale.US, "H %03d", deg);
    }

    private String formatBearingDelta(double heading, double selfLat, double selfLon,
            double wpLat, double wpLon) {
        double bearing = computeBearing(selfLat, selfLon, wpLat, wpLon);
        int bearingDeg = ((int) Math.round(bearing)) % 360;
        if (bearingDeg < 0) bearingDeg += 360;

        int headingDeg = Double.isNaN(heading) ? 0 : ((int) Math.round(heading)) % 360;
        if (headingDeg < 0) headingDeg += 360;

        int delta = bearingDeg - headingDeg;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;

        return String.format(Locale.US, "B%03d D%+d", bearingDeg, delta);
    }

    /** Haversine great-circle distance between two lat/lon points (metres). */
    private double computeHaversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    private double computeBearing(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double lat1r = Math.toRadians(lat1);
        double lat2r = Math.toRadians(lat2);
        double y = Math.sin(dLon) * Math.cos(lat2r);
        double x = Math.cos(lat1r) * Math.sin(lat2r)
                - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    private HudData noData() {
        return new HudData("H ---", "NAV --", "ALT ---", "---", "0m", "ALT ---");
    }
}
