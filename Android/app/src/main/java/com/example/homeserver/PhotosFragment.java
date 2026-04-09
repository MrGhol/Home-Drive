package com.example.homeserver;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.homeserver.data.api.ApiService;
import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.data.models.PhotoFolder;
import com.example.homeserver.databinding.FragmentPhotosBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@UnstableApi
public class PhotosFragment extends Fragment {

    private FragmentPhotosBinding binding;
    private BrowserAdapter adapter;
    private final Stack<String> pathStack = new Stack<>();
    private String currentPath = "photos"; // Start from "photos" path
    private int currentSpanCount = 3;
    private ScaleGestureDetector scaleGestureDetector;
    
    private final List<PhotoFolder> cachedFolders = new ArrayList<>();
    private final List<MediaFile> cachedFiles = new ArrayList<>();
    
    private Parcelable recyclerViewState;
    private String savedSearchQuery = "";

    public PhotosFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        SharedPreferences prefs = requireContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        currentSpanCount = prefs.getInt("grid_span_count", 3);

        requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter != null && adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                } else if (!pathStack.isEmpty() && pathStack.size() > 1) { // Only go back if we're not at the root "photos"
                    pathStack.pop();
                    currentPath = pathStack.peek();
                    recyclerViewState = null;
                    loadContent(currentPath);
                } else {
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // Listen for swiped position from Viewer
        getParentFragmentManager().setFragmentResultListener("viewer_result", this, (requestKey, result) -> {
            int lastPosition = result.getInt("last_position", -1);
            if (lastPosition != -1 && binding != null) {
                binding.rec1.post(() -> binding.rec1.scrollToPosition(lastPosition));
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPhotosBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        setupRecyclerView();
        setupDynamicGrid();
        
        adapter = new BrowserAdapter(new BrowserAdapter.OnItemClickListener() {
            @Override
            public void onFolderClick(PhotoFolder folder) {
                navigateTo(folder.getName());
            }

            @Override
            public void onFileClick(MediaFile file, int position, View sharedElement) {
                if (isAdded()) {
                    // Save scroll state before leaving
                    if (binding.rec1.getLayoutManager() != null) {
                        recyclerViewState = binding.rec1.getLayoutManager().onSaveInstanceState();
                    }
                    
                    String transitionName = ViewCompat.getTransitionName(sharedElement);
                    getParentFragmentManager()
                        .beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(sharedElement, transitionName != null ? transitionName : "")
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                        .replace(R.id.fragmentContainerView, ViewerFragment.newInstance(new ArrayList<>(adapter.getMediaFiles()), position))
                        .addToBackStack(null)
                        .commit();
                }
            }

            @Override
            public void onSelectionChanged(int count) {
                if (count > 0) {
                    binding.selectionToolbar.setVisibility(View.VISIBLE);
                    binding.selectionToolbar.setTitle(count + " selected");
                } else {
                    binding.selectionToolbar.setVisibility(View.GONE);
                    adapter.setSelectionMode(false);
                }
            }
        });
        
        binding.selectionToolbar.setNavigationOnClickListener(v -> adapter.setSelectionMode(false));
        binding.selectionToolbar.setOnMenuItemClickListener(this::onSelectionMenuItemClick);

        binding.rec1.setAdapter(adapter);
        binding.rec1.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    prewarmVisibleThumbnails();
                }
            }
        });

        binding.retryButton.setOnClickListener(v -> loadContent(currentPath));
        binding.swipeRefresh.setOnRefreshListener(() -> loadContent(currentPath));
        
        setupSmartSearch();

        // Restore UI state
        if (!savedSearchQuery.isEmpty()) {
            binding.searchInput.setText(savedSearchQuery);
        }

        // Initialize path stack if empty
        if (pathStack.isEmpty()) {
            pathStack.push("photos");
        }

        // If we have cached items, show them immediately to allow scroll restoration
        if (!cachedFolders.isEmpty() || !cachedFiles.isEmpty()) {
            showContent(cachedFolders, cachedFiles);
            if (recyclerViewState != null && binding.rec1.getLayoutManager() != null) {
                binding.rec1.getLayoutManager().onRestoreInstanceState(recyclerViewState);
            }
        }
        
        loadContent(currentPath);
    }

    private void setupRecyclerView() {
        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), currentSpanCount);
        binding.rec1.setLayoutManager(layoutManager);
        binding.rec1.setHasFixedSize(true);
    }

    private void setupDynamicGrid() {
        scaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (detector.getScaleFactor() > 1.15f && currentSpanCount > 2) {
                    updateSpanCount(currentSpanCount - 1);
                    return true;
                } else if (detector.getScaleFactor() < 0.85f && currentSpanCount < 6) {
                    updateSpanCount(currentSpanCount + 1);
                    return true;
                }
                return false;
            }
        });

        binding.rec1.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return false;
        });
    }

    private void updateSpanCount(int newSpan) {
        if (newSpan == currentSpanCount) return;
        currentSpanCount = newSpan;
        
        GridLayoutManager lm = (GridLayoutManager) binding.rec1.getLayoutManager();
        if (lm != null) {
            lm.setSpanCount(currentSpanCount);
            adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        }

        SharedPreferences prefs = requireContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        prefs.edit().putInt("grid_span_count", currentSpanCount).apply();
    }

    private void prewarmVisibleThumbnails() {
        GridLayoutManager lm = (GridLayoutManager) binding.rec1.getLayoutManager();
        if (lm == null) return;

        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;

        List<Object> items = adapter.getFilteredItemsList();
        SharedPreferences prefs = requireContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        if (baseUrl.isEmpty()) return;
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        for (int i = last + 1; i <= last + 6 && i < items.size(); i++) {
            Object item = items.get(i);
            if (item instanceof MediaFile) {
                MediaFile file = (MediaFile) item;
                String thumbEndpoint = file.getMime().startsWith("video/") ? "thumbnail/video/" : "thumbnail/";
                String thumbUrl = baseUrl + thumbEndpoint + file.getRelpath();
                
                Glide.with(this).load(thumbUrl).preload(320, 320);
            }
        }
    }

    private void setupSmartSearch() {
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                savedSearchQuery = s.toString().trim();
                if (currentPath.equals("photos")) {
                    performGlobalSearch(savedSearchQuery);
                } else {
                    adapter.applyFilter(savedSearchQuery, "image");
                    updateInsights();
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        binding.btnSort.setOnClickListener(this::showSortMenu);
        binding.btnFilter.setOnClickListener(this::showFilterMenu);
    }

    private void performGlobalSearch(String query) {
        if (query.isEmpty()) {
            filterAndShowContent(cachedFolders, cachedFiles);
            updateInsights();
            return;
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        RetrofitClient.getApiService(requireContext()).search(query).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Response<ApiService.MediaListResponse> response) {
                if (!isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null) {
                    filterAndShowContent(new ArrayList<>(), response.body().files);
                    updateInsights();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Throwable t) {
                if (!isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void showSortMenu(View v) {
        PopupMenu popup = new PopupMenu(getContext(), v);
        popup.getMenu().add("Name");
        popup.getMenu().add("Newest");
        popup.getMenu().add("Largest");
        popup.setOnMenuItemClickListener(item -> {
            adapter.applySort(item.getTitle().toString().toLowerCase());
            updateInsights();
            return true;
        });
        popup.show();
    }

    private void showFilterMenu(View v) {
        PopupMenu popup = new PopupMenu(getContext(), v);
        popup.getMenu().add("All Items");
        popup.getMenu().add("Images Only");
        popup.getMenu().add("Videos Only");
        popup.setOnMenuItemClickListener(item -> {
            String filterType = "image";
            String title = item.getTitle().toString();
            if (title.contains("Images")) filterType = "image";
            else if (title.contains("Videos")) filterType = "video";
            else filterType = "all";
            
            adapter.applyFilter(binding.searchInput.getText().toString(), filterType);
            updateInsights();
            return true;
        });
        popup.show();
    }

    private void updateInsights() {
        List<Object> items = adapter.getFilteredItemsList();
        if (items.isEmpty()) {
            binding.insightsCard.setVisibility(View.GONE);
            return;
        }
        binding.insightsCard.setVisibility(View.VISIBLE);

        long totalSize = 0;
        int imageCount = 0;
        int videoCount = 0;
        int folderCount = 0;
        int totalNestedFiles = 0;
        long maxFileBytes = -1;
        String largestFileName = "N/A";

        for (Object item : items) {
            if (item instanceof MediaFile) {
                MediaFile file = (MediaFile) item;
                totalSize += file.getSizeBytes();
                if (file.getMime().startsWith("image/")) imageCount++;
                else if (file.getMime().startsWith("video/")) videoCount++;

                if (file.getSizeBytes() > maxFileBytes) {
                    maxFileBytes = file.getSizeBytes();
                    largestFileName = file.getName();
                }
            } else if (item instanceof PhotoFolder) {
                PhotoFolder folder = (PhotoFolder) item;
                folderCount++;
                totalSize += folder.getSizeBytes();
                totalNestedFiles += folder.getFileCount();
            }
        }

        if (currentPath.equals("photos")) {
            binding.insightsSummary.setText(String.format(Locale.getDefault(), "%d Categories | %d Total Items", folderCount, totalNestedFiles));
        } else if (folderCount > 0) {
            binding.insightsSummary.setText(String.format(Locale.getDefault(), "%d Folders | %d Sub-items", folderCount, totalNestedFiles));
        } else {
            binding.insightsSummary.setText(String.format(Locale.getDefault(), "%d Images, %d Videos", imageCount, videoCount));
        }
        
        binding.insightsDetails.setText(String.format(Locale.getDefault(), "Total size: %s | Largest: %s", formatSize(totalSize), largestFileName));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private boolean onSelectionMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        Set<Object> selected = adapter.getSelectedItems();
        
        if (id == R.id.action_delete) {
            confirmDelete(selected);
            return true;
        } else if (id == R.id.action_share) {
            shareItems(selected);
            return true;
        } else if (id == R.id.action_download) {
            downloadItems(selected);
            return true;
        }
        return false;
    }

    private void confirmDelete(Set<Object> selected) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Items")
                .setMessage("Are you sure you want to delete " + selected.size() + " items?")
                .setPositiveButton("Delete", (dialog, which) -> deleteSelectedItems(selected))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSelectedItems(Set<Object> selected) {
        binding.progressBar.setVisibility(View.VISIBLE);
        int total = selected.size();
        final int[] completed = {0};

        for (Object item : selected) {
            String relPath = "";
            if (item instanceof MediaFile) relPath = ((MediaFile) item).getRelpath();
            else if (item instanceof PhotoFolder) relPath = "folders/photos/" + ((PhotoFolder) item).getName();

            RetrofitClient.getApiService(requireContext()).deleteFile(relPath).enqueue(new Callback<>() {
                @Override
                public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                    completed[0]++;
                    if (completed[0] == total) {
                        adapter.setSelectionMode(false);
                        loadContent(currentPath);
                    }
                }

                @Override
                public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                    completed[0]++;
                    if (completed[0] == total) {
                        adapter.setSelectionMode(false);
                        loadContent(currentPath);
                    }
                }
            });
        }
    }

    private void shareItems(Set<Object> selected) {
        ArrayList<Uri> uris = new ArrayList<>();
        SharedPreferences prefs = requireContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        
        for (Object item : selected) {
            if (item instanceof MediaFile) {
                String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "media/" + ((MediaFile) item).getRelpath();
                uris.add(Uri.parse(url));
            }
        }

        if (uris.isEmpty()) return;

        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("image/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        startActivity(Intent.createChooser(intent, "Share Media"));
    }

    private void downloadItems(Set<Object> selected) {
        Toast.makeText(getContext(), "Downloading " + selected.size() + " items...", Toast.LENGTH_SHORT).show();
    }

    private void navigateTo(String path) {
        pathStack.push(path);
        currentPath = path;
        recyclerViewState = null;
        loadContent(currentPath);
    }

    private void loadContent(String path) {
        updateBreadcrumbs(path);
        
        // Only show loading if cache is empty
        if (cachedFolders.isEmpty() && cachedFiles.isEmpty()) {
            showLoading();
        }
        
        RetrofitClient.getApiService(requireContext()).browse(path).enqueue(new Callback<ApiService.BrowseResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiService.BrowseResponse> call, @NonNull Response<ApiService.BrowseResponse> response) {
                if (!isAdded() || binding == null) return;
                
                binding.swipeRefresh.setRefreshing(false);
                
                if (response.isSuccessful() && response.body() != null) {
                    List<PhotoFolder> folders = response.body().folders;
                    List<MediaFile> files = response.body().files;
                    
                    cachedFolders.clear();
                    cachedFolders.addAll(folders);
                    cachedFiles.clear();
                    cachedFiles.addAll(files);

                    if (folders.isEmpty() && files.isEmpty()) {
                        showEmpty();
                    } else {
                        filterAndShowContent(folders, files);
                        
                        // Restore scroll position if it exists
                        if (recyclerViewState != null && binding.rec1.getLayoutManager() != null) {
                            binding.rec1.getLayoutManager().onRestoreInstanceState(recyclerViewState);
                            recyclerViewState = null;
                        }
                    }
                } else {
                    showError("Failed to load content: " + response.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiService.BrowseResponse> call, @NonNull Throwable t) {
                if (!isAdded() || binding == null) return;
                binding.swipeRefresh.setRefreshing(false);
                showError("Server error: " + t.getMessage());
            }
        });
    }

    private void updateBreadcrumbs(String path) {
        binding.breadcrumbContainer.removeAllViews();
        addBreadcrumb("Home", "photos");
        
        if (path != null && !path.isEmpty() && !path.equals("photos")) {
            // Strip the leading "photos/" from the path for breadcrumb display
            String displayPath = path.startsWith("photos/") ? path.substring(7) : path;
            String[] parts = displayPath.split("/");
            StringBuilder currentBuild = new StringBuilder("photos");
            for (String part : parts) {
                if (part.isEmpty()) continue;
                currentBuild.append("/").append(part);
                
                TextView separator = new TextView(getContext());
                separator.setText(" / ");
                binding.breadcrumbContainer.addView(separator);
                
                final String targetPath = currentBuild.toString();
                addBreadcrumb(part, targetPath);
            }
        }
        
        binding.breadcrumbScroll.post(() -> binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void addBreadcrumb(String title, String path) {
        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setPadding(16, 8, 16, 8);
        tv.setBackgroundResource(R.drawable.shape_rounded_translucent);
        tv.setOnClickListener(v -> {
            if (!currentPath.equals(path)) {
                while (!pathStack.isEmpty() && !pathStack.peek().equals(path)) {
                    pathStack.pop();
                }
                currentPath = path;
                recyclerViewState = null;
                loadContent(currentPath);
            }
        });
        binding.breadcrumbContainer.addView(tv);
    }

    private void showLoading() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.rec1.setVisibility(View.GONE);
        binding.errorLayout.setVisibility(View.GONE);
        binding.emptyLayout.setVisibility(View.GONE);
        binding.insightsCard.setVisibility(View.GONE);
    }

    private void filterAndShowContent(List<PhotoFolder> folders, List<MediaFile> files) {
        List<MediaFile> filteredFiles = new ArrayList<>();
        for (MediaFile file : files) {
            if (file.getMime().startsWith("image/")) {
                filteredFiles.add(file);
            }
        }
        showContent(folders, filteredFiles);
    }

    private void showContent(List<PhotoFolder> folders, List<MediaFile> files) {
        binding.progressBar.setVisibility(View.GONE);
        binding.rec1.setVisibility(View.VISIBLE);
        binding.errorLayout.setVisibility(View.GONE);
        binding.emptyLayout.setVisibility(View.GONE);
        
        adapter.setItems(folders, files);
        // Important: Ensure we apply the image filter so folders are still shown but non-images are hidden
        adapter.applyFilter("", "image");

        updateInsights();
        prewarmVisibleThumbnails();
    }

    private void showEmpty() {
        binding.progressBar.setVisibility(View.GONE);
        binding.rec1.setVisibility(View.GONE);
        binding.errorLayout.setVisibility(View.GONE);
        binding.emptyLayout.setVisibility(View.VISIBLE);
        binding.insightsCard.setVisibility(View.GONE);
    }

    private void showError(String message) {
        binding.progressBar.setVisibility(View.GONE);
        binding.rec1.setVisibility(View.GONE);
        binding.errorLayout.setVisibility(View.VISIBLE);
        binding.emptyLayout.setVisibility(View.GONE);
        binding.errorText.setText(message);
        binding.insightsCard.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}