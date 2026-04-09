package com.example.homeserver;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.homeserver.data.api.ApiService;
import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.databinding.FragmentSearchBinding;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@UnstableApi
public class SearchFragment extends Fragment {

    private FragmentSearchBinding binding;
    private MediaAdapter mainAdapter;
    private MediaAdapter searchAdapter;
    private List<MediaFile> searchResults = new ArrayList<>();
    
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private String currentMimeType = null;

    public SearchFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSearchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupAdapters();
        setupSearchView();
        setupChips();
    }

    private void setupAdapters() {
        OnMediaClickListener listener = new OnMediaClickListener();
        
        mainAdapter = new MediaAdapter(listener);
        searchAdapter = new MediaAdapter(listener);

        binding.searchRecyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
        binding.searchRecyclerView.setAdapter(mainAdapter);

        binding.searchResultsRecycler.setLayoutManager(new GridLayoutManager(getContext(), 3));
        binding.searchResultsRecycler.setAdapter(searchAdapter);
    }

    private void setupSearchView() {
        binding.searchView.getEditText().setOnEditorActionListener((v, actionId, event) -> {
            String query = binding.searchView.getText().toString();
            performSearch(query);
            return false;
        });

        binding.searchView.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchHandler.removeCallbacks(searchRunnable);
                searchRunnable = () -> performSearch(s.toString());
                searchHandler.postDelayed(searchRunnable, 400); // 400ms debounce
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupChips() {
        binding.filterChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chipImages)) {
                currentMimeType = "image/";
            } else if (checkedIds.contains(R.id.chipVideos)) {
                currentMimeType = "video/";
            } else {
                currentMimeType = null;
            }
            performSearch(binding.searchView.getText().toString());
        });
    }

    private void performSearch(String query) {
        if (query.isEmpty()) {
            searchResults.clear();
            searchAdapter.setMediaFiles(searchResults);
            return;
        }

        binding.searchProgressBar.setVisibility(View.VISIBLE);
        
        // Note: Backend currently only supports 'q' query. 
        // We'll filter the results client-side for mimeType for now as requested.
        RetrofitClient.getApiService(requireContext())
                .search(query)
                .enqueue(new Callback<ApiService.MediaListResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Response<ApiService.MediaListResponse> response) {
                        if (isAdded() && binding != null) {
                            binding.searchProgressBar.setVisibility(View.GONE);
                            if (response.isSuccessful() && response.body() != null) {
                                List<MediaFile> allResults = response.body().files;
                                List<MediaFile> filteredResults = new ArrayList<>();
                                
                                for (MediaFile file : allResults) {
                                    if (currentMimeType == null || file.getMime().startsWith(currentMimeType)) {
                                        filteredResults.add(file);
                                    }
                                }
                                
                                searchResults = filteredResults;
                                searchAdapter.setMediaFiles(searchResults);
                            }
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiService.MediaListResponse> call, @NonNull Throwable t) {
                        if (isAdded() && binding != null) {
                            binding.searchProgressBar.setVisibility(View.GONE);
                        }
                    }
                });
    }

    private class OnMediaClickListener implements MediaAdapter.OnMediaClickListener {
        @Override
        public void onMediaClick(MediaFile mediaFile, int position) {
            if (getActivity() != null) {
                getParentFragmentManager()
                    .beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                    .replace(R.id.fragmentContainerView, ViewerFragment.newInstance(new ArrayList<>(searchResults), position))
                    .addToBackStack(null)
                    .commit();
            }
        }

        @Override
        public void onSelectionChanged(int count) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}