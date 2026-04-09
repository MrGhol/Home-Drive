package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.data.models.PhotoFolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BrowserAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_FOLDER = 0;
    private static final int TYPE_FILE = 1;

    private final List<Object> allItems = new ArrayList<>();
    private final List<Object> filteredItems = new ArrayList<>();
    private final Set<Object> selectedItems = new HashSet<>();
    private boolean selectionMode = false;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onFolderClick(PhotoFolder folder);
        void onFileClick(MediaFile file, int position, View sharedElement);
        void onSelectionChanged(int count);
    }

    public BrowserAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<PhotoFolder> folders, List<MediaFile> files) {
        allItems.clear();
        allItems.addAll(folders);
        allItems.addAll(files);
        // Default to showing everything we were given
        filteredItems.clear();
        filteredItems.addAll(allItems);
        notifyDataSetChanged();
    }

    public List<Object> getFilteredItemsList() {
        return new ArrayList<>(filteredItems);
    }

    public void applyFilter(String query, String type) {
        filteredItems.clear();
        String lowerQuery = query.toLowerCase();
        
        for (Object item : allItems) {
            boolean matchesQuery = false;
            boolean matchesType = true;

            if (item instanceof PhotoFolder) {
                PhotoFolder folder = (PhotoFolder) item;
                matchesQuery = folder.getName().toLowerCase().contains(lowerQuery);
                // Folders are always kept during filtering to allow navigation, 
                // unless we specifically want to hide them (not implemented here)
                matchesType = true;
            } else if (item instanceof MediaFile) {
                MediaFile file = (MediaFile) item;
                matchesQuery = file.getName().toLowerCase().contains(lowerQuery);
                if (type.equals("image")) {
                    matchesType = file.getMime().startsWith("image/");
                } else if (type.equals("video")) {
                    matchesType = file.getMime().startsWith("video/");
                } else if (type.equals("all")) {
                    matchesType = true;
                } else {
                    matchesType = false;
                }
            }

            if (matchesQuery && matchesType) {
                filteredItems.add(item);
            }
        }
        notifyDataSetChanged();
    }

    public void applySort(String criteria) {
        filteredItems.sort((o1, o2) -> {
            if (criteria.equals("name")) {
                String n1 = getName(o1);
                String n2 = getName(o2);
                return n1.compareToIgnoreCase(n2);
            } else if (criteria.equals("newest")) {
                return getTime(o2).compareTo(getTime(o1)); // Descending
            } else if (criteria.equals("size")) {
                return Long.compare(getSize(o2), getSize(o1)); // Descending
            }
            return 0;
        });
        notifyDataSetChanged();
    }

    private String getName(Object o) {
        if (o instanceof PhotoFolder) return ((PhotoFolder) o).getName();
        if (o instanceof MediaFile) return ((MediaFile) o).getName();
        return "";
    }

    private String getTime(Object o) {
        if (o instanceof MediaFile) return ((MediaFile) o).getMtime();
        return "";
    }

    private long getSize(Object o) {
        if (o instanceof PhotoFolder) return ((PhotoFolder) o).getSizeBytes();
        if (o instanceof MediaFile) return ((MediaFile) o).getSizeBytes();
        return 0;
    }

    public List<MediaFile> getMediaFiles() {
        List<MediaFile> files = new ArrayList<>();
        for (Object item : filteredItems) {
            if (item instanceof MediaFile) {
                files.add((MediaFile) item);
            }
        }
        return files;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean mode) {
        if (this.selectionMode != mode) {
            this.selectionMode = mode;
            if (!mode) selectedItems.clear();
            notifyDataSetChanged();
            listener.onSelectionChanged(selectedItems.size());
        }
    }

    public void toggleSelection(int position) {
        Object item = filteredItems.get(position);
        if (selectedItems.contains(item)) {
            selectedItems.remove(item);
        } else {
            selectedItems.add(item);
        }
        notifyItemChanged(position);
        listener.onSelectionChanged(selectedItems.size());
        
        if (selectedItems.isEmpty()) {
            selectionMode = false;
            notifyDataSetChanged();
            listener.onSelectionChanged(0);
        }
    }

    public void clearSelection() {
        selectionMode = false;
        selectedItems.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged(0);
    }

    public Set<Object> getSelectedItems() {
        return new HashSet<>(selectedItems);
    }

    @Override
    public int getItemViewType(int position) {
        return filteredItems.get(position) instanceof PhotoFolder ? TYPE_FOLDER : TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_FOLDER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_folder, parent, false);
            return new FolderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo, parent, false);
            return new FileViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = filteredItems.get(position);
        boolean isSelected = selectedItems.contains(item);

        if (holder instanceof FolderViewHolder) {
            bindFolder((FolderViewHolder) holder, (PhotoFolder) item, isSelected, position);
        } else {
            bindFile((FileViewHolder) holder, (MediaFile) item, position, isSelected);
        }
    }

    private void bindFolder(FolderViewHolder holder, PhotoFolder folder, boolean isSelected, int position) {
        String displayName = folder.getName();
        if (displayName.contains("/")) {
            displayName = displayName.substring(displayName.lastIndexOf("/") + 1);
        }
        holder.name.setText(displayName);
        
        if (folder.getFileCount() > 0) {
            holder.count.setVisibility(View.VISIBLE);
            holder.count.setText(folder.getFileCount() + " items");
        } else {
            holder.count.setVisibility(View.GONE);
        }

        if (folder.getPreview() != null) {
            SharedPreferences prefs = holder.itemView.getContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
            String baseUrl = prefs.getString("server_ip", "");
            String thumbUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "thumbnail/" + folder.getPreview();

            Glide.with(holder.itemView.getContext())
                    .load(thumbUrl)
                    .centerCrop()
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .into(holder.preview);
        } else {
            holder.preview.setImageResource(android.R.drawable.ic_menu_gallery);
            holder.preview.setPadding(40, 40, 40, 40);
        }

        holder.itemView.setAlpha(isSelected ? 0.6f : 1.0f);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(position);
            } else {
                listener.onFolderClick(folder);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                selectionMode = true;
                toggleSelection(position);
                return true;
            }
            return false;
        });
    }

    private void bindFile(FileViewHolder holder, MediaFile file, int position, boolean isSelected) {
        SharedPreferences prefs = holder.itemView.getContext().getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        
        String thumbEndpoint = file.getMime().startsWith("video/") ? "thumbnail/video/" : "thumbnail/";
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String thumbUrl = baseUrl + thumbEndpoint + file.getRelpath();

        // Unique transition name for shared element animation
        ViewCompat.setTransitionName(holder.image, "media_thumb_" + file.getRelpath());

        Glide.with(holder.itemView.getContext())
                .load(thumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .signature(new ObjectKey(file.getMtime()))
                .override(320, 320)
                .centerCrop()
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(holder.image);

        if (file.getMime().startsWith("video/")) {
            holder.videoIndicator.setVisibility(View.VISIBLE);
            holder.videoScrim.setVisibility(View.VISIBLE);
        } else {
            holder.videoIndicator.setVisibility(View.GONE);
            holder.videoScrim.setVisibility(View.GONE);
        }
        
        holder.selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        holder.selectionCheck.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        holder.selectionCheck.setChecked(isSelected);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(position);
            } else {
                int fileIndex = 0;
                for (int i = 0; i < position; i++) {
                    if (filteredItems.get(i) instanceof MediaFile) fileIndex++;
                }
                listener.onFileClick(file, fileIndex, holder.image);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                selectionMode = true;
                toggleSelection(position);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return filteredItems.size();
    }

    static class FolderViewHolder extends RecyclerView.ViewHolder {
        ImageView preview;
        TextView name, count;
        FolderViewHolder(View v) {
            super(v);
            preview = v.findViewById(R.id.folderPreview);
            name = v.findViewById(R.id.folderName);
            count = v.findViewById(R.id.fileCount);
        }
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView image, videoIndicator;
        View selectionOverlay, videoScrim;
        CheckBox selectionCheck;
        FileViewHolder(View v) {
            super(v);
            image = v.findViewById(R.id.photoImage);
            videoIndicator = v.findViewById(R.id.videoIndicator);
            selectionOverlay = v.findViewById(R.id.selectionOverlay);
            selectionCheck = v.findViewById(R.id.selectionCheck);
            videoScrim = v.findViewById(R.id.videoScrim);
        }
    }
}