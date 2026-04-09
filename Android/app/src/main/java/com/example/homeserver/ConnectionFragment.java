package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.ServerInfo;
import com.example.homeserver.databinding.FragmentConnectionBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ConnectionFragment extends Fragment {

    private FragmentConnectionBinding binding;
    private DiscoveryManager discoveryManager;
    private DiscoveredServiceAdapter discoveryAdapter;
    private static final String PREFS_NAME = "HomeServerPrefs";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_SERVER_ID = "server_id";

    public enum ConnectionState {
        NOT_CONFIGURED,
        DISCOVERING,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentConnectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupDiscoveryList();
        loadStoredConfig();

        binding.connectButton.setOnClickListener(v -> {
            String ip = binding.ipInput.getText().toString().trim();
            String apiKey = binding.apiKeyInput.getText().toString().trim();
            if (ip.isEmpty()) {
                binding.ipInputLayout.setError("IP address is required");
                return;
            }
            if (!ip.startsWith("http")) ip = "http://" + ip;
            if (!ip.endsWith("/")) ip += "/";

            verifyAndConnect(ip, apiKey);
        });

        startDiscovery();
    }

    private void setupDiscoveryList() {
        discoveryAdapter = new DiscoveredServiceAdapter(service -> {
            String ip = "http://" + service.getHost().getHostAddress() + ":" + service.getPort() + "/";
            binding.ipInput.setText(ip);
            verifyAndConnect(ip, binding.apiKeyInput.getText().toString().trim());
        });
        binding.discoveryRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.discoveryRecyclerView.setAdapter(discoveryAdapter);
    }

    private void startDiscovery() {
        updateState(ConnectionState.DISCOVERING);
        discoveryManager = new DiscoveryManager(requireContext(), new DiscoveryManager.DiscoveryCallback() {
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> discoveryAdapter.addService(serviceInfo));
                }
            }

            @Override
            public void onDiscoveryStopped() {
                Log.d("ConnectionFragment", "Discovery stopped");
            }

            @Override
            public void onError(String message) {
                Log.e("ConnectionFragment", message);
            }
        });
        discoveryManager.startDiscovery();
    }

    private void verifyAndConnect(String ip, String apiKey) {
        if (ip.startsWith("http://") && !NetworkUtils.isPrivateOrLocalHost(ip)) {
            updateState(ConnectionState.FAILED);
            Toast.makeText(getContext(), "Refusing insecure HTTP for non-local host. Use HTTPS or a LAN address.", Toast.LENGTH_LONG).show();
            return;
        }

        updateState(ConnectionState.CONNECTING);
        
        // Temporarily set IP in prefs for Retrofit client to use it
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_IP, ip).putString(KEY_API_KEY, apiKey).apply();
        RetrofitClient.resetClient();

        RetrofitClient.getApiService(requireContext()).getHealth().enqueue(new Callback<ServerInfo>() {
            @Override
            public void onResponse(@NonNull Call<ServerInfo> call, @NonNull Response<ServerInfo> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ServerInfo info = response.body();
                    String storedId = prefs.getString(KEY_SERVER_ID, "");
                    
                    if (!storedId.isEmpty() && !storedId.equals(info.getServerId())) {
                        // Different server ID detected
                        Toast.makeText(getContext(), "Connected to a different server instance.", Toast.LENGTH_LONG).show();
                    }
                    
                    prefs.edit().putString(KEY_SERVER_ID, info.getServerId()).apply();
                    updateState(ConnectionState.CONNECTED);
                    
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).onConfigurationSaved();
                    }
                } else {
                    updateState(ConnectionState.FAILED);
                    Toast.makeText(getContext(), "Invalid Home Drive server", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ServerInfo> call, @NonNull Throwable t) {
                updateState(ConnectionState.FAILED);
                Toast.makeText(getContext(), "Connection failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateState(ConnectionState state) {
        if (binding == null) return;
        binding.statusText.setText("Status: " + state.name());
        binding.progressBar.setVisibility(state == ConnectionState.CONNECTING ? View.VISIBLE : View.GONE);
    }

    private void loadStoredConfig() {
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        binding.ipInput.setText(prefs.getString(KEY_IP, ""));
        binding.apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (discoveryManager != null) discoveryManager.stopDiscovery();
        binding = null;
    }

    // Inner Adapter for Discovery List
    private static class DiscoveredServiceAdapter extends RecyclerView.Adapter<DiscoveredServiceAdapter.ViewHolder> {
        private final List<NsdServiceInfo> services = new ArrayList<>();
        private final OnServiceClickListener listener;

        interface OnServiceClickListener { void onServiceClick(NsdServiceInfo service); }
        DiscoveredServiceAdapter(OnServiceClickListener listener) { this.listener = listener; }

        void addService(NsdServiceInfo service) {
            for (NsdServiceInfo s : services) {
                if (s.getServiceName().equals(service.getServiceName())) return;
            }
            services.add(service);
            notifyItemInserted(services.size() - 1);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_discovered_service, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            NsdServiceInfo s = services.get(position);
            holder.name.setText(s.getServiceName());
            holder.address.setText(s.getHost().getHostAddress() + ":" + s.getPort());
            holder.itemView.setOnClickListener(v -> listener.onServiceClick(s));
        }

        @Override
        public int getItemCount() { return services.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, address;
            ViewHolder(View v) { super(v); name = v.findViewById(R.id.serviceName); address = v.findViewById(R.id.serviceAddress); }
        }
    }
}
