package com.example.homeserver;

import android.net.Uri;

public final class NetworkUtils {

    private NetworkUtils() {}

    public static boolean isPrivateOrLocalHost(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.trim().isEmpty()) return false;
        String host = urlOrHost;

        if (urlOrHost.contains("://")) {
            Uri uri = Uri.parse(urlOrHost);
            host = uri.getHost();
        }

        if (host == null || host.trim().isEmpty()) return false;

        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".local")) return true;
        if (h.startsWith("127.")) return true;

        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        try {
            int p0 = Integer.parseInt(parts[0]);
            int p1 = Integer.parseInt(parts[1]);

            if (p0 == 10) return true;
            if (p0 == 192 && p1 == 168) return true;
            if (p0 == 172 && p1 >= 16 && p1 <= 31) return true;
        } catch (NumberFormatException ignored) {
            return false;
        }

        return false;
    }
}
