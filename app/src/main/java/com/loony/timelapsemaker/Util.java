package com.loony.timelapsemaker;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created by Kamil on 11/13/2016.
 */

public class Util {
    public static void log(String str, Object... params) {
        String formated = params.length == 0 ? str : String.format(str, params);

        if(params.length == 0)
            Log.d("test", formated);
        else
            Log.d("test", formated);
    }

    public static void logEx(String tag, String str, Object... params) {
        String formated = params.length == 0 ? str : String.format(str, params);

        if(params.length == 0)
            Log.d(tag, formated);
        else
            Log.d(tag, formated);
    }

    public static void logToast(Context context, String str, Object... params) {
        String formated = params.length == 0 ? str : String.format(str, params);
        Log.d("test", formated);
        Toast.makeText(context, formated, Toast.LENGTH_SHORT).show();
    }

    public static boolean isMyServiceRunning(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static <K, V> String mapToString(final String label, final String newline, Map<K, V> map) {
        String output = String.format("%s: %s", label, newline);

        for(Map.Entry<K, V> entry : map.entrySet()) {
            log("Map.Entry key: '%s'; value: '%s'", entry.getKey(), entry.getValue());
            output += String.format("- '%s' -> '%s'%s", entry.getKey(), entry.getValue(), newline);
        }

        return output;
    }

    public static String getLocalIpAddress(boolean useIPv4) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        //boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
                        boolean isIPv4 = sAddr.indexOf(':')<0;

                        if (useIPv4) {
                            if (isIPv4)
                                return sAddr;
                        } else {
                            if (!isIPv4) {
                                int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                                return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) { } // for now eat exceptions
        return "";
    }

    public static float getBatteryLevel(Context context) {
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        // Error checking that probably isn't needed but I added just in case.
        if(level == -1 || scale == -1) {
            return 50.0f;
        }

        return ((float)level / (float)scale) * 100.0f;
    }

    public static int calcRemainingTimeAsSeconds(long afAverageTime, int lastCapturedAmount, int totalFramesAmount, float frequencyCapture) {
        int differenceFrames = totalFramesAmount - lastCapturedAmount;
        int afAvgSec = (int) afAverageTime / 1000;
        int res = (int) Math.ceil(differenceFrames * (frequencyCapture + afAvgSec) - afAvgSec);
        return res < 0 ? 0 : res;
    }
}
