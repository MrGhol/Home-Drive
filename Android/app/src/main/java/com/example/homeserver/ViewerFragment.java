package com.example.homeserver;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.TransitionInflater;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.databinding.FragmentViewerBinding;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@UnstableApi
public class ViewerFragment extends Fragment implements ViewerAdapter.OnPhotoInteractionListener {

    private FragmentViewerBinding binding;
    private ExoPlayer player;
    private List<MediaFile> mediaFiles;
    private int startPosition;
    private int currentPosition;
    private final Map<String, Long> playbackPositions = new HashMap<>();
    private boolean isUiVisible = true;
    private BottomSheetBehavior<View> exifBehavior;
    
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideUi;
    private static final long AUTO_HIDE_DELAY = 3500;

    private GestureDetector gestureDetector;

    public static ViewerFragment newInstance(ArrayList<MediaFile> files, int position) {
        ViewerFragment fragment = new ViewerFragment();
        Bundle args = new Bundle();
        args.putSerializable("files", files);
        args.putInt("position", position);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        postponeEnterTransition();
        setSharedElementEnterTransition(TransitionInflater.from(requireContext()).inflateTransition(android.R.transition.move));
        setSharedElementReturnTransition(TransitionInflater.from(requireContext()).inflateTransition(android.R.transition.move));

        if (getArguments() != null) {
            Object files = getArguments().getSerializable("files");
            if (files instanceof List) {
                @SuppressWarnings("unchecked")
                List<MediaFile> list = (List<MediaFile>) files;
                mediaFiles = list;
            }
            startPosition = getArguments().getInt("position");
            currentPosition = startPosition;
        }

        setupGestures();
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                toggleUi();
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                showQuickActions();
            }

            @Override
            public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null) return false;
                float diffY = e2.getY() - e1.getY();
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffY) > Math.abs(diffX)) {
                    if (diffY > 150 && Math.abs(velocityY) > 100) {
                        // Swipe Down: Dismiss
                        getParentFragmentManager().popBackStack();
                        return true;
                    } else if (diffY < -150 && Math.abs(velocityY) > 100) {
                        // Swipe Up: Show Metadata
                        exifBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void showQuickActions() {
        PopupMenu popup = new PopupMenu(requireContext(), binding.viewerToolbar);
        popup.getMenu().add("Save to Device");
        popup.getMenu().add("Set as Wallpaper");
        popup.getMenu().add("Copy URL");
        popup.setOnMenuItemClickListener(item -> {
            Toast.makeText(getContext(), item.getTitle() + " selected", Toast.LENGTH_SHORT).show();
            return true;
        });
        popup.show();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentViewerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Window window = requireActivity().getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        
        // Use full screen including cutout
        window.getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        // Apply insets to the AppBarLayout to avoid overlapping with status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, systemBars.top, 0, 0);
            return insets;
        });

        // Apply insets to the exif drawer (bottom sheet)
        ViewCompat.setOnApplyWindowInsetsListener(binding.exifDrawer, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            float density = getResources().getDisplayMetrics().density;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), systemBars.bottom + (int)(16 * density));
            return insets;
        });

        exifBehavior = BottomSheetBehavior.from(binding.exifDrawer);
        exifBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        exifBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    cancelAutoHide();
                } else if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    resetAutoHide();
                }
            }
            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });

        binding.viewerToolbar.setNavigationOnClickListener(v -> getParentFragmentManager().popBackStack());
        binding.viewerToolbar.inflateMenu(R.menu.menu_viewer);
        binding.viewerToolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        setupViewer();
        
        // Connect gestures to the root container (for areas not covered by ViewPager/Player)
        binding.getRoot().setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return true;
        });

        // Set initial background for UI visible state
        binding.getRoot().setBackgroundColor(Color.parseColor("#CC000000"));

        if (mediaFiles != null && !mediaFiles.isEmpty()) {
            MediaFile current = mediaFiles.get(currentPosition);
            if (current.getMime().startsWith("video/")) {
                hideHandler.postDelayed(hideRunnable, 1000);
            } else {
                resetAutoHide();
            }
        }
    }

    private void setupViewer() {
        if (mediaFiles == null || mediaFiles.isEmpty()) return;

        ViewerAdapter adapter = new ViewerAdapter(mediaFiles, this);
        binding.viewPager.setAdapter(adapter);
        binding.viewPager.setCurrentItem(startPosition, false);

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPosition = position;
                updateToolbar(position);
                handleMediaPlayback(position);
                updateExifData(mediaFiles.get(position));
                resetAutoHide();
                
                Bundle result = new Bundle();
                result.putInt("last_position", position);
                getParentFragmentManager().setFragmentResult("viewer_result", result);
            }
        });

        updateToolbar(startPosition);
        handleMediaPlayback(startPosition);
        updateExifData(mediaFiles.get(startPosition));
    }

    @Override
    public void onPhotoClick() {
        toggleUi();
    }

    @Override
    public void onImageLoaded(int position) {
        if (position == startPosition) {
            binding.viewPager.post(this::startPostponedEnterTransition);
        }
    }

    private void updateToolbar(int position) {
        MediaFile currentFile = mediaFiles.get(position);
        binding.viewerToolbar.setTitle(currentFile.getName());
    }

    private void handleMediaPlayback(int position) {
        MediaFile currentFile = mediaFiles.get(position);
        if (currentFile.getMime().startsWith("video/")) {
            showVideo(currentFile);
        } else {
            stopVideo();
            binding.viewPager.setVisibility(View.VISIBLE);
            binding.playerView.setVisibility(View.GONE);
        }
    }

    private void showVideo(MediaFile file) {
        binding.viewPager.setVisibility(View.GONE);
        binding.playerView.setVisibility(View.VISIBLE);

        if (player != null) {
            stopVideo();
        }

        SharedPreferences prefs = requireContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        String videoUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "media/" + file.getRelpath();

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 60000, 1000, 2000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        HttpDataSource.Factory httpDataSourceFactory = new OkHttpDataSource.Factory(okHttpClient);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(requireContext(), httpDataSourceFactory);

        player = new ExoPlayer.Builder(requireContext())
                .setLoadControl(loadControl)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        
        binding.playerView.setPlayer(player);
        binding.playerView.setControllerAutoShow(true);
        binding.playerView.setControllerShowTimeoutMs(3000);
        
        // Sync Toolbar with Player Controller
        binding.playerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override
            public void onVisibilityChanged(int visibility) {
                if (visibility == View.VISIBLE) {
                    showUi();
                } else {
                    if (isUiVisible) resetAutoHide();
                }
            }
        });

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Toast.makeText(getContext(), "Playback Error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) resetAutoHide();
                else cancelAutoHide();
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(videoUrl);
        player.setMediaItem(mediaItem);
        
        Long lastPos = playbackPositions.get(file.getRelpath());
        if (lastPos != null) {
            player.seekTo(lastPos);
        }

        player.prepare();
        player.play();
        
        binding.playerView.post(this::startPostponedEnterTransition);
    }

    private void stopVideo() {
        if (player != null) {
            int pos = currentPosition;
            if (pos < mediaFiles.size()) {
                MediaFile currentFile = mediaFiles.get(pos);
                playbackPositions.put(currentFile.getRelpath(), player.getCurrentPosition());
            }
            player.release();
            player = null;
        }
    }

    private void updateExifData(MediaFile file) {
        String info = "File: " + file.getName() + "\n" +
                "Path: " + file.getRelpath() + "\n" +
                "Size: " + file.getSizeHuman() + "\n" +
                "Date: " + file.getMtime() + "\n" +
                "Type: " + file.getMime();
        binding.exifInfo.setText(info);
    }

    public void toggleUi() {
        if (isUiVisible) hideUi();
        else showUi();
    }

    private void hideUi() {
        if (!isUiVisible || !isAdded()) return;
        
        isUiVisible = false;
        cancelAutoHide();

        binding.appBarLayout.animate()
                .alpha(0f)
                .translationY(-binding.appBarLayout.getHeight())
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (binding != null) binding.appBarLayout.setVisibility(View.GONE);
                })
                .start();

        animateBackgroundColor(Color.parseColor("#CC000000"), Color.BLACK);

        if (player != null && binding.playerView.isControllerFullyVisible()) {
            binding.playerView.hideController();
        }

        Window window = getActivity() != null ? getActivity().getWindow() : null;
        if (window != null) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void showUi() {
        if (isUiVisible || !isAdded()) return;

        isUiVisible = true;
        binding.appBarLayout.setVisibility(View.VISIBLE);
        
        binding.appBarLayout.animate()
                .alpha(1f)
                .translationY(0)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        animateBackgroundColor(Color.BLACK, Color.parseColor("#CC000000"));

        if (player != null && !binding.playerView.isControllerFullyVisible()) {
            binding.playerView.showController();
        }

        Window window = getActivity() != null ? getActivity().getWindow() : null;
        if (window != null) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
        
        resetAutoHide();
    }

    private void animateBackgroundColor(int fromColor, int toColor) {
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, toColor);
        anim.setDuration(300);
        anim.addUpdateListener(animation -> {
            if (binding != null) {
                binding.getRoot().setBackgroundColor((int) animation.getAnimatedValue());
            }
        });
        anim.start();
    }

    private void resetAutoHide() {
        cancelAutoHide();
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY);
    }

    private void cancelAutoHide() {
        hideHandler.removeCallbacks(hideRunnable);
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_delete) {
            confirmDelete();
            return true;
        } else if (id == R.id.action_info) {
            exifBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            return true;
        } else if (id == R.id.action_share) {
            shareCurrentFile();
            return true;
        } else if (id == R.id.action_download) {
            downloadCurrentFile();
            return true;
        }
        return false;
    }

    private void shareCurrentFile() {
        MediaFile file = mediaFiles.get(currentPosition);
        SharedPreferences prefs = requireContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        String fileUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "media/" + file.getRelpath();

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(file.getMime());
        intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(fileUrl));
        startActivity(Intent.createChooser(intent, "Share via"));
    }

    private void downloadCurrentFile() {
        MediaFile file = mediaFiles.get(currentPosition);
        Toast.makeText(getContext(), "Downloading: " + file.getName(), Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete() {
        MediaFile currentFile = mediaFiles.get(currentPosition);
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete File")
                .setMessage("Are you sure you want to delete " + currentFile.getName() + "?")
                .setPositiveButton("Delete", (dialog, which) -> deleteCurrentFile(currentFile, currentPosition))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteCurrentFile(MediaFile file, int position) {
        RetrofitClient.getApiService(requireContext()).deleteFile(file.getRelpath()).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                if (isAdded() && response.isSuccessful()) {
                    Toast.makeText(getContext(), "Deleted", Toast.LENGTH_SHORT).show();
                    mediaFiles.remove(position);
                    if (mediaFiles.isEmpty()) getParentFragmentManager().popBackStack();
                    else setupViewer();
                }
            }
            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {}
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        stopVideo();
        Window window = getActivity() != null ? getActivity().getWindow() : null;
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, true);
        }
        showUi();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelAutoHide();
        binding = null;
    }
}