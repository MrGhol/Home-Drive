package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.example.homeserver.data.models.PhotoFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

    private List<PhotoFolder> folders = new ArrayList<>();
    private final OnFolderClickListener listener;
    private final Random random = new Random();

    public interface OnFolderClickListener {
        void onFolderClick(PhotoFolder folder);
        void onFolderDelete(PhotoFolder folder);
    }

    public FolderAdapter(OnFolderClickListener listener) {
        this.listener = listener;
    }

    public void setFolders(List<PhotoFolder> folders) {
        this.folders = folders;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_folder, parent, false);
        return new FolderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        PhotoFolder folder = folders.get(position);
        holder.folderName.setText(folder.getName());
        
        // Show delete button only if folder is empty
        if (folder.getFileCount() == 0) {
            holder.btnDeleteFolder.setVisibility(View.VISIBLE);
            holder.fileCount.setVisibility(View.VISIBLE);
            holder.fileCount.setText("Empty folder");
        } else {
            holder.btnDeleteFolder.setVisibility(View.GONE);
            holder.fileCount.setVisibility(View.GONE);
        }

        if (folder.getPreview() != null) {
            SharedPreferences prefs = holder.itemView.getContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
            String baseUrl = prefs.getString("server_ip", "");
            
            String thumbUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "thumbnail/" + folder.getPreview();

            Glide.with(holder.itemView.getContext())
                    .load(thumbUrl)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .signature(new ObjectKey(System.currentTimeMillis() / 60000))
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .into(holder.folderPreview);
        } else {
            holder.folderPreview.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        holder.itemView.setOnClickListener(v -> listener.onFolderClick(folder));
        holder.btnDeleteFolder.setOnClickListener(v -> listener.onFolderDelete(folder));
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    static class FolderViewHolder extends RecyclerView.ViewHolder {
        ImageView folderPreview;
        TextView folderName;
        TextView fileCount;
        ImageButton btnDeleteFolder;

        public FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            folderPreview = itemView.findViewById(R.id.folderPreview);
            folderName = itemView.findViewById(R.id.folderName);
            fileCount = itemView.findViewById(R.id.fileCount);
            btnDeleteFolder = itemView.findViewById(R.id.btnDeleteFolder);
        }
    }
}
