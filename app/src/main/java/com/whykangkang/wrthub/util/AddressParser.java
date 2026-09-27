package com.whykangkang.wrthub.util;

import java.util.Locale;

/**
 * 地址解析:支持 "192.168.1.1"、"192.168.1.1:8080"、"http(s)://host:port/path"。
 * 对应 iOS AddEditDeviceViewController 的地址解析逻辑。
 */
public final class AddressParser {

    public static final class ParsedAddress {
        public final String host;
        public final int port;
        public final boolean useHttps;
        /** 输入是否显式给了端口(未给时端口是按 scheme 推的默认值) */
        public final boolean explicitPort;

        ParsedAddress(String host, int port, boolean useHttps, boolean explicitPort) {
            this.host = host;
            this.port = port;
            this.useHttps = useHttps;
            this.explicitPort = explicitPort;
        }
    }

    private AddressParser() {
    }

    /** 解析失败(空/端口非法)返回 null */
    public static ParsedAddress parse(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return null;
        }

        boolean https = false;
        boolean hasScheme = false;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            https = true;
            hasScheme = true;
            s = s.substring(8);
        } else if (lower.startsWith("http://")) {
            hasScheme = true;
            s = s.substring(7);
        }

        // 去掉路径部分
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        if (s.isEmpty()) {
            return null;
        }

        String host = s;
        int port = https ? 443 : 80;
        boolean explicitPort = false;
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            String portStr = s.substring(colon + 1);
            try {
                int p = Integer.parseInt(portStr);
                if (p < 1 || p > 65535) {
                    return null;
                }
                port = p;
                explicitPort = true;
                host = s.substring(0, colon);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (host.isEmpty()) {
            return null;
        }
        // 无 scheme 且无端口:默认 http:80(与 iOS 一致)
        if (!hasScheme && !explicitPort) {
            port = 80;
        }
        return new ParsedAddress(host, port, https, explicitPort);
    }
}