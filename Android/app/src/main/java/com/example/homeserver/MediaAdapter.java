package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.example.homeserver.data.models.MediaFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {

    private final List<MediaFile> mediaFiles = new ArrayList<>();
    private final Set<String> selectedPaths = new HashSet<>();
    private final OnMediaClickListener listener;
    private boolean selectionMode = false;

    public interface OnMediaClickListener {
        void onMediaClick(MediaFile mediaFile, int position);
        void onSelectionChanged(int count);
    }

    public MediaAdapter(OnMediaClickListener listener) {
        this.listener = listener;
    }

    public void setMediaFiles(List<MediaFile> newList) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new MediaDiffCallback(this.mediaFiles, newList));
        this.mediaFiles.clear();
        this.mediaFiles.addAll(newList);
        diffResult.dispatchUpdatesTo(this);
    }

    public void addMediaFiles(List<MediaFile> moreFiles) {
        int startPos = this.mediaFiles.size();
        this.mediaFiles.addAll(moreFiles);
        notifyItemRangeInserted(startPos, moreFiles.size());
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo, parent, false);
        return new MediaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        MediaFile file = mediaFiles.get(position);
        
        SharedPreferences prefs = holder.itemView.getContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        
        String thumbEndpoint = file.getMime().startsWith("video/") ? "/thumbnail/video/" : "/thumbnail/";
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String thumbUrl = baseUrl + thumbEndpoint.substring(1) + file.getRelpath();

        Glide.with(holder.itemView.getContext())
                .load(thumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .signature(new ObjectKey(file.getMtime())) // Use mtime to invalidate stale placeholders
                .override(320, 320)
                .centerCrop()
                .placeholder(R.drawable.photo1)
                .error(R.drawable.photo1)
                .into(holder.photoImage);

        holder.videoIndicator.setVisibility(file.getMime().startsWith("video/") ? View.VISIBLE : View.GONE);
        
        // Selection UI
        boolean isSelected = selectedPaths.contains(file.getRelpath());
        holder.selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        holder.itemView.setAlpha(isSelected ? 0.7f : 1.0f);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(file.getRelpath(), position);
            } else {
                listener.onMediaClick(file, position);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                selectionMode = true;
                toggleSelection(file.getRelpath(), position);
                return true;
            }
            return false;
        });
    }

    private void toggleSelection(String path, int position) {
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path);
        } else {
            selectedPaths.add(path);
        }
        notifyItemChanged(position);
        listener.onSelectionChanged(selectedPaths.size());
        
        if (selectedPaths.isEmpty()) {
            selectionMode = false;
        }
    }

    public void clearSelection() {
        selectionMode = false;
        selectedPaths.clear();
        notifyDataSetChanged();
    }

    public Set<String> getSelectedPaths() {
        return new HashSet<>(selectedPaths);
    }

    @Override
    public int getItemCount() {
        return mediaFiles.size();
    }

    public List<MediaFile> getMediaFiles() {
        return mediaFiles;
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        ImageView photoImage;
        ImageView videoIndicator;
        View selectionOverlay;

        public MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            photoImage = itemView.findViewById(R.id.photoImage);
            videoIndicator = itemView.findViewById(R.id.videoIndicator);
            selectionOverlay = itemView.findViewById(R.id.selectionOverlay);
        }
    }

    private static class MediaDiffCallback extends DiffUtil.Callback {
        private final List<MediaFile> oldList;
        private final List<MediaFile> newList;

        public MediaDiffCallback(List<MediaFile> oldList, List<MediaFile> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }
        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return Objects.equals(oldList.get(oldItemPosition).getRelpath(), newList.get(newItemPosition).getRelpath());
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            MediaFile oldFile = oldList.get(oldItemPosition);
            MediaFile newFile = newList.get(newItemPosition);
            return Objects.equals(oldFile.getName(), newFile.getName()) &&
                   Objects.equals(oldFile.getMtime(), newFile.getMtime());
        }
    }
}