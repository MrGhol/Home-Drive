package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.ServerInfo;
import com.example.homeserver.databinding.FragmentSettingsBinding;

import java.util.concurrent.TimeUnit;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private SharedPreferences prefs;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private boolean suppressAutoBackupToggle = false;
    private static final String PREFS_NAME = "HomeServerPrefs";
    private static final String KEY_THEME = "app_theme";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_SERVER_ID = "server_id";
    private static final String KEY_AUTO_BACKUP = "auto_backup";
    private static final String KEY_WIFI_ONLY = "backup_wifi_only";
    private static final String KEY_CHARGING_ONLY = "backup_charging_only";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (!isAdded() || binding == null) return;
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }
                    if (allGranted) {
                        scheduleBackup(prefs);
                        Toast.makeText(getContext(), "Auto Backup Enabled", Toast.LENGTH_SHORT).show();
                    } else {
                        suppressAutoBackupToggle = true;
                        binding.switchAutoBackup.setChecked(false);
                        suppressAutoBackupToggle = false;
                        prefs.edit().putBoolean(KEY_AUTO_BACKUP, false).apply();
                        updateBackupSwitchState(false);
                        Toast.makeText(getContext(), "Permission required for auto backup", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // --- IP Management ---
        binding.ipInput.setText(prefs.getString(KEY_IP, ""));
        binding.saveIpButton.setOnClickListener(v -> {
            String newIp = binding.ipInput.getText().toString().trim();
            if (newIp.isEmpty()) {
                binding.ipInputLayout.setError("IP cannot be empty");
                return;
            }
            if (!newIp.startsWith("http")) newIp = "http://" + newIp;
            if (!newIp.endsWith("/")) newIp += "/";
            if (newIp.startsWith("http://") && !NetworkUtils.isPrivateOrLocalHost(newIp)) {
                binding.ipInputLayout.setError("HTTP allowed only on local network. Use HTTPS or a LAN address.");
                return;
            }

            prefs.edit().putString(KEY_IP, newIp).apply();
            RetrofitClient.resetClient();
            Toast.makeText(getContext(), "IP Updated to: " + newIp, Toast.LENGTH_SHORT).show();
            fetchServerInfo(prefs);
        });

        // --- Version Sync Awareness ---
        fetchServerInfo(prefs);

        // --- Auto Backup Management ---
        binding.switchAutoBackup.setChecked(prefs.getBoolean(KEY_AUTO_BACKUP, false));
        binding.switchWifiOnly.setChecked(prefs.getBoolean(KEY_WIFI_ONLY, true));
        binding.switchChargingOnly.setChecked(prefs.getBoolean(KEY_CHARGING_ONLY, false));

        updateBackupSwitchState(binding.switchAutoBackup.isChecked());

        binding.switchAutoBackup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressAutoBackupToggle) return;
            prefs.edit().putBoolean(KEY_AUTO_BACKUP, isChecked).apply();
            updateBackupSwitchState(isChecked);
            if (isChecked) {
                if (hasMediaReadPermission()) {
                    scheduleBackup(prefs);
                    Toast.makeText(getContext(), "Auto Backup Enabled", Toast.LENGTH_SHORT).show();
                } else {
                    requestMediaPermissions();
                }
            } else {
                cancelBackup();
            }
        });

        binding.switchWifiOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, isChecked).apply();
            if (binding.switchAutoBackup.isChecked()) scheduleBackup(prefs);
        });

        binding.switchChargingOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_CHARGING_ONLY, isChecked).apply();
            if (binding.switchAutoBackup.isChecked()) scheduleBackup(prefs);
        });

        // --- Theme Management ---
        int savedTheme = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (savedTheme == AppCompatDelegate.MODE_NIGHT_NO) {
            binding.themeToggleGroup.check(R.id.buttonLight);
        } else if (savedTheme == AppCompatDelegate.MODE_NIGHT_YES) {
            binding.themeToggleGroup.check(R.id.buttonDark);
        } else {
            binding.themeToggleGroup.check(R.id.buttonSystem);
        }

        binding.themeToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                int themeMode;
                if (checkedId == R.id.buttonLight) {
                    themeMode = AppCompatDelegate.MODE_NIGHT_NO;
                } else if (checkedId == R.id.buttonDark) {
                    themeMode = AppCompatDelegate.MODE_NIGHT_YES;
                } else {
                    themeMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                }

                prefs.edit().putInt(KEY_THEME, themeMode).apply();
                AppCompatDelegate.setDefaultNightMode(themeMode);
            }
        });
    }

    private void fetchServerInfo(SharedPreferences prefs) {
        String savedServerId = prefs.getString(KEY_SERVER_ID, "");
        
        RetrofitClient.getApiService(requireContext()).getHealth().enqueue(new Callback<ServerInfo>() {
            @Override
            public void onResponse(@NonNull Call<ServerInfo> call, @NonNull Response<ServerInfo> response) {
                if (isAdded() && binding != null && response.isSuccessful() && response.body() != null) {
                    ServerInfo info = response.body();
                    binding.serverInfoCard.setVisibility(View.VISIBLE);
                    binding.serverNameText.setText("Server: " + info.getName());
                    binding.serverVersionText.setText("Backend Version: " + info.getVersion());
                    binding.serverIdText.setText("ID: " + info.getServerId());

                    if (!savedServerId.isEmpty() && !savedServerId.equals(info.getServerId())) {
                        binding.serverMismatchWarning.setVisibility(View.VISIBLE);
                    } else {
                        binding.serverMismatchWarning.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<ServerInfo> call, @NonNull Throwable t) {
                if (isAdded() && binding != null) {
                    binding.serverInfoCard.setVisibility(View.GONE);
                }
            }
        });
    }

    private void updateBackupSwitchState(boolean isEnabled) {
        binding.switchWifiOnly.setEnabled(isEnabled);
        binding.switchChargingOnly.setEnabled(isEnabled);
    }

    private void scheduleBackup(SharedPreferences prefs) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(prefs.getBoolean(KEY_WIFI_ONLY, true) ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .setRequiresCharging(prefs.getBoolean(KEY_CHARGING_ONLY, false))
                .build();

        PeriodicWorkRequest backupRequest = new PeriodicWorkRequest.Builder(AutoBackupWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("auto_backup_task")
                .build();

        WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork(
                "AutoBackup",
                ExistingPeriodicWorkPolicy.REPLACE,
                backupRequest
        );
    }

    private void cancelBackup() {
        WorkManager.getInstance(requireContext()).cancelUniqueWork("AutoBackup");
        Toast.makeText(getContext(), "Auto Backup Disabled", Toast.LENGTH_SHORT).show();
    }

    private boolean hasMediaReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int img = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_MEDIA_IMAGES);
            int vid = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_MEDIA_VIDEO);
            return img == PackageManager.PERMISSION_GRANTED && vid == PackageManager.PERMISSION_GRANTED;
        } else {
            int read = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_EXTERNAL_STORAGE);
            return read == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestMediaPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[] {
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
            });
        } else {
            permissionLauncher.launch(new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
