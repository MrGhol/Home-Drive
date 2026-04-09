package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.bumptech.glide.util.FixedPreloadSizeProvider;
import com.example.homeserver.data.api.ApiService;
import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.databinding.FragmentLibraryBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LibraryFragment extends Fragment {

    private FragmentLibraryBinding binding;
    private MediaAdapter adapter;
    private List<MediaFile> videoFiles = new ArrayList<>();
    private int skip = 0;
    private static final int LIMIT = 60;
    private boolean isLoading = false;
    private boolean isLastPage = false;

    public LibraryFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new MediaAdapter(new MediaAdapter.OnMediaClickListener() {
            @Override
            public void onMediaClick(MediaFile mediaFile, int position) {
                if (isAdded()) {
                    getParentFragmentManager()
                        .beginTransaction()
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                        .replace(R.id.fragmentContainerView, ViewerFragment.newInstance(new ArrayList<>(adapter.getMediaFiles()), position))
                        .addToBackStack(null)
                        .commit();
                }
            }

            @Override
            public void onSelectionChanged(int count) {
                // Batch selection could be added here later
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        binding.libraryRecyclerView.setLayoutManager(layoutManager);
        binding.libraryRecyclerView.setHasFixedSize(true);
        binding.libraryRecyclerView.setAdapter(adapter);

        setupPreloader();

        binding.libraryRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0) return; 

                int visibleItemCount = layoutManager.getChildCount();
                int totalItemCount = layoutManager.getItemCount();
                int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();

                if (!isLoading && !isLastPage) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 15
                            && firstVisibleItemPosition >= 0) {
                        loadVideos();
                    }
                }
            }
        });

        loadVideos();
    }

    private void setupPreloader() {
        ListPreloader.PreloadModelProvider<MediaFile> preloadModelProvider = new ListPreloader.PreloadModelProvider<MediaFile>() {
            @NonNull
            @Override
            public List<MediaFile> getPreloadItems(int position) {
                List<MediaFile> currentFiles = adapter.getMediaFiles();
                if (position >= currentFiles.size()) return Collections.emptyList();
                return Collections.singletonList(currentFiles.get(position));
            }

            @Nullable
            @Override
            public RequestBuilder<?> getPreloadRequestBuilder(@NonNull MediaFile item) {
                Context context = getContext();
                if (context == null) return null;

                SharedPreferences prefs = context.getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
                String baseUrl = prefs.getString("server_ip", "");
                if (baseUrl.isEmpty()) return null;

                if (!baseUrl.endsWith("/")) baseUrl += "/";
                String thumbUrl = baseUrl + "thumbnail/video/" + item.getRelpath();

                return Glide.with(LibraryFragment.this)
                        .load(thumbUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .signature(new ObjectKey(item.getMtime())) // Use mtime to invalidate stale placeholders
                        .override(320, 320)
                        .centerCrop();
            }
        };

        RecyclerViewPreloader<MediaFile> preloader = new RecyclerViewPreloader<>(
                Glide.with(this), preloadModelProvider, new FixedPreloadSizeProvider<>(320, 320), 20);
        binding.libraryRecyclerView.addOnScrollListener(preloader);
    }

    private void loadVideos() {
        if (isLoading || isLastPage) return;
        isLoading = true;
        binding.libraryProgressBar.setVisibility(View.VISIBLE);

        RetrofitClient.getApiService(requireContext())
                .getVideos(skip, LIMIT)
                .enqueue(new Callback<ApiService.MediaListResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Response<ApiService.MediaListResponse> response) {
                        if (!isAdded() || binding == null) return;
                        isLoading = false;
                        binding.libraryProgressBar.setVisibility(View.GONE);

                        if (response.isSuccessful() && response.body() != null) {
                            List<MediaFile> newFiles = response.body().files;
                            if (newFiles.isEmpty()) {
                                isLastPage = true;
                            } else {
                                if (skip == 0) {
                                    adapter.setMediaFiles(newFiles);
                                } else {
                                    adapter.addMediaFiles(newFiles);
                                }
                                skip += newFiles.size();
                                if (newFiles.size() < LIMIT) isLastPage = true;
                            }
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Throwable t) {
                        if (!isAdded() || binding == null) return;
                        isLoading = false;
                        binding.libraryProgressBar.setVisibility(View.GONE);
                        Log.e("LibraryFragment", "Error: " + t.getMessage());
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}