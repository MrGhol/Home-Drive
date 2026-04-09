package com.example.homeserver;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

public class DiscoveryManager {
    private static final String TAG = "DiscoveryManager";
    private static final String SERVICE_TYPE = "_http._tcp.";
    private static final String SERVICE_NAME_PREFIX = "HomeDrive";

    private final NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final DiscoveryCallback callback;

    public interface DiscoveryCallback {
        void onServiceFound(NsdServiceInfo serviceInfo);
        void onDiscoveryStopped();
        void onError(String message);
    }

    public DiscoveryManager(Context context, DiscoveryCallback callback) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.callback = callback;
    }

    public void startDiscovery() {
        stopDiscovery();
        
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                callback.onError("Discovery failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "mDNS discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                callback.onDiscoveryStopped();
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo.getServiceName().contains(SERVICE_NAME_PREFIX)) {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.e(TAG, "Resolve failed: " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolvedServiceInfo) {
                            callback.onServiceFound(resolvedServiceInfo);
                        }
                    });
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {}
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping discovery", e);
            }
            discoveryListener = null;
        }
    }
}