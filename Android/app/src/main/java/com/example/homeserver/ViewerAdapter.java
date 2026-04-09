package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.homeserver.data.models.MediaFile;
import com.github.chrisbanes.photoview.PhotoView;

import java.util.List;

public class ViewerAdapter extends RecyclerView.Adapter<ViewerAdapter.ViewHolder> {

    private final List<MediaFile> mediaFiles;
    private final OnPhotoInteractionListener listener;

    public interface OnPhotoInteractionListener {
        void onPhotoClick();
        void onImageLoaded(int position);
    }

    public ViewerAdapter(List<MediaFile> mediaFiles, OnPhotoInteractionListener listener) {
        this.mediaFiles = mediaFiles;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_viewer_image, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MediaFile file = mediaFiles.get(position);
        
        SharedPreferences prefs = holder.itemView.getContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        
        String imageUrl = baseUrl + "media/" + file.getRelpath();

        // Important: Shared element transition name must be set on the specific view
        ViewCompat.setTransitionName(holder.photoView, "media_thumb_" + file.getRelpath());

        holder.progressBar.setVisibility(View.VISIBLE);

        Glide.with(holder.itemView.getContext())
                .load(imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .dontAnimate() // Important for shared element transitions
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        holder.progressBar.setVisibility(View.GONE);
                        if (listener != null) listener.onImageLoaded(holder.getAbsoluteAdapterPosition());
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        holder.progressBar.setVisibility(View.GONE);
                        if (listener != null) listener.onImageLoaded(holder.getAbsoluteAdapterPosition());
                        return false;
                    }
                })
                .into(holder.photoView);

        holder.photoView.setOnViewTapListener((view, x, y) -> {
            if (listener != null) listener.onPhotoClick();
        });
    }

    @Override
    public int getItemCount() {
        return mediaFiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        PhotoView photoView;
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            photoView = itemView.findViewById(R.id.photoView);
            progressBar = itemView.findViewById(R.id.imageProgressBar);
        }
    }
}