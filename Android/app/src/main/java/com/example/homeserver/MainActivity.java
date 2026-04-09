package com.example.homeserver;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.MemoryCategory;
import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.ServerInfo;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "HomeServerPrefs";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_THEME = "app_theme";
    private static final String KEY_SERVER_ID = "server_id";
    
    private DrawerLayout drawerLayout;
    private BottomNavigationView bottomNav;
    private FloatingActionButton fabUpload;
    private View connectionIndicator;
    private MaterialToolbar topAppBar;
    private DiscoveryManager discoveryManager;
    private boolean isReconnecting = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkServerConnection();
            handler.postDelayed(this, 30000); 
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int savedTheme = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(savedTheme);

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        optimizeGlideMemory();

        drawerLayout = findViewById(R.id.drawer_layout);
        bottomNav = findViewById(R.id.bottom);
        fabUpload = findViewById(R.id.fabUpload);
        connectionIndicator = findViewById(R.id.connectionIndicator);
        topAppBar = findViewById(R.id.topAppBar);
        NavigationView navigationView = findViewById(R.id.nav_view);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        topAppBar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        topAppBar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_search) {
                bottomNav.setSelectedItemId(R.id.nav_search);
                return true;
            }
            return false;
        });

        navigationView.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_settings) {
                navigateToFragment(new SettingsFragment(), true);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        setupNavigation();

        fabUpload.setOnClickListener(v -> navigateToFragment(new UploadFragment(), true));

        String serverIp = prefs.getString(KEY_IP, "");

        if (serverIp.isEmpty()) {
            showConnectionScreen();
            startAutoDiscovery(null);
        } else {
            showInitialFragment();
            handler.post(healthCheckRunnable);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    private void optimizeGlideMemory() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null && activityManager.isLowRamDevice()) {
            Glide.get(this).setMemoryCategory(MemoryCategory.LOW);
        } else {
            Glide.get(this).setMemoryCategory(MemoryCategory.NORMAL);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) {
            Glide.get(this).clearMemory();
        }
    }

    private void navigateToFragment(Fragment fragment, boolean addToBackstack) {
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragmentContainerView, fragment);
        
        if (addToBackstack) {
            transaction.addToBackStack(null);
        }
        transaction.commit();
    }

    private void startAutoDiscovery(String targetServerId) {
        if (discoveryManager != null) discoveryManager.stopDiscovery();
        
        discoveryManager = new DiscoveryManager(this, new DiscoveryManager.DiscoveryCallback() {
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String discoveredIp = "http://" + serviceInfo.getHost().getHostAddress() + ":" + serviceInfo.getPort() + "/";
                runOnUiThread(() -> verifyAndConnect(discoveredIp, targetServerId));
            }

            @Override
            public void onDiscoveryStopped() {}

            @Override
            public void onError(String message) {
                Log.e(TAG, "mDNS error: " + message);
            }
        });
        discoveryManager.startDiscovery();
    }

    private void verifyAndConnect(String ip, String targetServerId) {
        RetrofitClient.getApiService(this).getHealth().enqueue(new Callback<ServerInfo>() {
            @Override
            public void onResponse(@NonNull Call<ServerInfo> call, @NonNull Response<ServerInfo> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ServerInfo info = response.body();
                    if (targetServerId != null && !targetServerId.equals(info.getServerId())) return;

                    saveServerConfig(ip, info.getServerId(), info.getName());
                    if (discoveryManager != null) discoveryManager.stopDiscovery();
                    
                    isReconnecting = false;
                    onConfigurationSaved();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ServerInfo> call, @NonNull Throwable t) {}
        });
    }

    private void saveServerConfig(String ip, String serverId, String serverName) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_IP, ip)
                .putString(KEY_SERVER_ID, serverId)
                .apply();
        RetrofitClient.resetClient();
        Toast.makeText(this, "Connected to " + serverName, Toast.LENGTH_SHORT).show();
    }

    private void setupNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            
            Fragment selectedFragment = null;
            int itemId = item.getItemId();

            if (itemId == R.id.nav_media) {
                selectedFragment = new TimelineFragment();
                fabUpload.setVisibility(View.GONE);
                topAppBar.setTitle("Media");
            } else if (itemId == R.id.nav_albums) {
                selectedFragment = new PhotosFragment();
                fabUpload.setVisibility(View.VISIBLE);
                topAppBar.setTitle("Albums");
            } else if (itemId == R.id.nav_search) {
                selectedFragment = new SearchFragment();
                fabUpload.setVisibility(View.GONE);
                topAppBar.setTitle("Search");
            } else if (itemId == R.id.nav_more) {
                selectedFragment = new SettingsFragment(); // Placeholder for More tab
                fabUpload.setVisibility(View.GONE);
                topAppBar.setTitle("More");
            }

            if (selectedFragment != null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                        .replace(R.id.fragmentContainerView, selectedFragment)
                        .commit();
                return true;
            }
            return false;
        });
    }

    private void checkServerConnection() {
        RetrofitClient.getApiService(this).getHealth().enqueue(new Callback<ServerInfo>() {
            @Override
            public void onResponse(@NonNull Call<ServerInfo> call, @NonNull Response<ServerInfo> response) {
                if (isFinishing()) return;
                updateStatusIndicator(response.isSuccessful());
                if (!response.isSuccessful() && !isReconnecting) attemptRediscovery();
            }

            @Override
            public void onFailure(@NonNull Call<ServerInfo> call, @NonNull Throwable t) {
                if (isFinishing()) return;
                updateStatusIndicator(false);
                if (!isReconnecting) attemptRediscovery();
            }
        });
    }

    private void attemptRediscovery() {
        isReconnecting = true;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String serverId = prefs.getString(KEY_SERVER_ID, "");
        if (!serverId.isEmpty()) startAutoDiscovery(serverId);
    }

    private void updateStatusIndicator(boolean connected) {
        connectionIndicator.setVisibility(View.VISIBLE);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(connected ? Color.GREEN : Color.RED);
        connectionIndicator.setBackground(shape);
    }

    private void showConnectionScreen() {
        bottomNav.setVisibility(View.GONE);
        fabUpload.setVisibility(View.GONE);
        navigateToFragment(new ConnectionFragment(), false);
    }

    private void showInitialFragment() {
        bottomNav.setVisibility(View.VISIBLE);
        bottomNav.setSelectedItemId(R.id.nav_media); // Default to Media (Timeline)
    }

    public void onConfigurationSaved() {
        showInitialFragment();
        handler.removeCallbacks(healthCheckRunnable);
        handler.post(healthCheckRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(healthCheckRunnable);
        if (discoveryManager != null) discoveryManager.stopDiscovery();
    }
}
