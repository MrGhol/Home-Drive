package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.util.FixedPreloadSizeProvider;
import com.example.homeserver.data.api.ApiService;
import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.databinding.FragmentMediaBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MediaFragment extends Fragment {

    private FragmentMediaBinding binding;
    private MediaAdapter adapter;
    private String folderName;
    private int skip = 0;
    private static final int LIMIT = 60;
    private boolean isLoading = false;
    private boolean isLastPage = false;

    public static MediaFragment newInstance(String folderName) {
        MediaFragment fragment = new MediaFragment();
        Bundle args = new Bundle();
        args.putString("folder_name", folderName);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            folderName = getArguments().getString("folder_name");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMediaBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.toolbar.setTitle(folderName);
        binding.toolbar.setNavigationOnClickListener(v -> {
            if (adapter != null && !adapter.getSelectedPaths().isEmpty()) {
                clearSelection();
            } else {
                requireActivity().onBackPressed();
            }
        });

        binding.toolbar.inflateMenu(R.menu.menu_viewer); // Reusing delete menu
        binding.toolbar.getMenu().findItem(R.id.action_delete).setVisible(false);
        binding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

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
                if (count > 0) {
                    binding.toolbar.setTitle(count + " selected");
                    binding.toolbar.getMenu().findItem(R.id.action_delete).setVisible(true);
                    binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
                } else {
                    binding.toolbar.setTitle(folderName);
                    binding.toolbar.getMenu().findItem(R.id.action_delete).setVisible(false);
                    binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
                }
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        binding.mediaRecyclerView.setLayoutManager(layoutManager);
        binding.mediaRecyclerView.setHasFixedSize(true);
        binding.mediaRecyclerView.setAdapter(adapter);

        setupPreloader();

        binding.mediaRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
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
                        loadMedia();
                    }
                }
            }
        });

        loadMedia();
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            confirmBatchDelete();
            return true;
        }
        return false;
    }

    private void confirmBatchDelete() {
        Set<String> selected = adapter.getSelectedPaths();
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Items")
                .setMessage("Delete " + selected.size() + " items from the server?")
                .setPositiveButton("Delete", (dialog, which) -> performBatchDelete(selected))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performBatchDelete(Set<String> paths) {
        binding.mediaProgressBar.setVisibility(View.VISIBLE);
        int total = paths.size();
        final int[] completed = {0};

        for (String path : paths) {
            RetrofitClient.getApiService(requireContext()).deleteFile(path).enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                    completed[0]++;
                    if (completed[0] == total) finalizeDelete();
                }

                @Override
                public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                    completed[0]++;
                    if (completed[0] == total) finalizeDelete();
                }
            });
        }
    }

    private void finalizeDelete() {
        if (isAdded() && binding != null) {
            binding.mediaProgressBar.setVisibility(View.GONE);
            clearSelection();
            skip = 0;
            isLastPage = false;
            loadMedia(); // Full refresh after batch delete
        }
    }

    private void clearSelection() {
        adapter.clearSelection();
        binding.toolbar.setTitle(folderName);
        binding.toolbar.getMenu().findItem(R.id.action_delete).setVisible(false);
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
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

                String thumbEndpoint = item.getMime().startsWith("video/") ? "thumbnail/video/" : "thumbnail/";
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                String thumbUrl = baseUrl + thumbEndpoint + item.getRelpath();

                return Glide.with(MediaFragment.this)
                        .load(thumbUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(320, 320)
                        .centerCrop();
            }
        };

        RecyclerViewPreloader<MediaFile> preloader = new RecyclerViewPreloader<>(
                Glide.with(this), preloadModelProvider, new FixedPreloadSizeProvider<>(320, 320), 20);
        binding.mediaRecyclerView.addOnScrollListener(preloader);
    }

    private void loadMedia() {
        if (isLoading || isLastPage) return;
        isLoading = true;
        binding.mediaProgressBar.setVisibility(View.VISIBLE);
        
        RetrofitClient.getApiService(requireContext())
                .getPhotosInFolder(folderName, skip, LIMIT)
                .enqueue(new Callback<ApiService.MediaListResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Response<ApiService.MediaListResponse> response) {
                        if (!isAdded() || binding == null) return;
                        
                        isLoading = false;
                        binding.mediaProgressBar.setVisibility(View.GONE);
                        
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
                        binding.mediaProgressBar.setVisibility(View.GONE);
                        Log.e("MediaFragment", "Error: " + t.getMessage());
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}