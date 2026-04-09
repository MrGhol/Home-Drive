package com.example.homeserver;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.homeserver.data.api.ApiService;
import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.databinding.FragmentTimelineBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@UnstableApi
public class TimelineFragment extends Fragment {

    private FragmentTimelineBinding binding;
    private TimelineAdapter adapter;
    private final List<MediaFile> allMediaList = new ArrayList<>();
    private int pendingRequests = 0;

    public TimelineFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentTimelineBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new TimelineAdapter((file, position) -> {
            if (isAdded()) {
                getParentFragmentManager()
                        .beginTransaction()
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                        .replace(R.id.fragmentContainerView, ViewerFragment.newInstance(new ArrayList<>(adapter.getOnlyMediaFiles()), position))
                        .addToBackStack(null)
                        .commit();
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        layoutManager.setSpanSizeLookup(adapter.getSpanSizeLookup(3));
        binding.timelineRecyclerView.setLayoutManager(layoutManager);
        binding.timelineRecyclerView.setAdapter(adapter);

        binding.swipeRefresh.setOnRefreshListener(this::loadAllMedia);

        loadAllMedia();
    }

    private void loadAllMedia() {
        if (!isAdded()) return;
        
        binding.progressBar.setVisibility(View.VISIBLE);
        allMediaList.clear();

        RetrofitClient.getApiService(requireContext()).search("").enqueue(new Callback<ApiService.MediaListResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Response<ApiService.MediaListResponse> response) {
                if (!isAdded() || binding == null) return;

                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);

                if (response.isSuccessful() && response.body() != null) {
                    List<MediaFile> files = response.body().files;
                    if (files == null || files.isEmpty()) {
                        fetchFromRootFolders();
                    } else {
                        updateTimeline(files);
                    }
                } else {
                    fetchFromRootFolders();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Throwable t) {
                if (!isAdded() || binding == null) return;
                fetchFromRootFolders();
            }
        });
    }

    private void fetchFromRootFolders() {
        allMediaList.clear();
        pendingRequests = 2;

        // 1. Fetch Videos
        RetrofitClient.getApiService(requireContext()).getVideos(0, 1000).enqueue(new Callback<ApiService.MediaListResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Response<ApiService.MediaListResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    synchronized (allMediaList) {
                        allMediaList.addAll(response.body().files);
                    }
                }
                checkRequestsComplete();
            }

            @Override
            public void onFailure(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Throwable t) {
                checkRequestsComplete();
            }
        });

        // 2. Fetch Photos from root "photos"
        RetrofitClient.getApiService(requireContext()).browse("photos").enqueue(new Callback<ApiService.BrowseResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiService.BrowseResponse> call, @NonNull Response<ApiService.BrowseResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    synchronized (allMediaList) {
                        allMediaList.addAll(response.body().files);
                    }
                }
                checkRequestsComplete();
            }

            @Override
            public void onFailure(@NonNull Call<ApiService.BrowseResponse> call, @NonNull Throwable t) {
                checkRequestsComplete();
            }
        });
    }

    private void checkRequestsComplete() {
        pendingRequests--;
        if (pendingRequests <= 0) {
            if (!isAdded() || binding == null) return;
            binding.progressBar.setVisibility(View.GONE);
            binding.swipeRefresh.setRefreshing(false);
            updateTimeline(allMediaList);
        }
    }

    private void updateTimeline(List<MediaFile> files) {
        if (files == null || files.isEmpty()) {
            // No results found
            adapter.setMediaFiles(new ArrayList<>());
            return;
        }
        
        // Sort combined list by modification time descending
        files.sort((f1, f2) -> {
            if (f1.getMtime() == null || f2.getMtime() == null) return 0;
            return f2.getMtime().compareTo(f1.getMtime());
        });
        adapter.setMediaFiles(new ArrayList<>(files));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
