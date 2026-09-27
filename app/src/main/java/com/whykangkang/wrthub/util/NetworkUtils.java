package com.whykangkang.wrthub.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;

/** 网络工具:取本机 IPv4(用 ConnectivityManager,无需定位权限) */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    public static String getLocalIpv4(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network network = cm.getActiveNetwork();
        if (network == null) return null;
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                return la.getAddress().getHostAddress();
            }
        }
        return null;
    }
}