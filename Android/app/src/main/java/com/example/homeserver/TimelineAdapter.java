package com.example.homeserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.example.homeserver.data.models.MediaFile;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class TimelineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final List<Object> items = new ArrayList<>();
    private final OnItemClickListener listener;
    private final SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    private final SimpleDateFormat headerFormat = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());

    public interface OnItemClickListener {
        void onItemClick(MediaFile file, int position);
    }

    public TimelineAdapter(OnItemClickListener listener) {
        this.listener = listener;
        inputFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public void setMediaFiles(List<MediaFile> files) {
        items.clear();
        if (files.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        String lastDate = "";
        for (MediaFile file : files) {
            String dateStr = formatDate(file.getMtime());
            if (!dateStr.equals(lastDate)) {
                items.add(dateStr);
                lastDate = dateStr;
            }
            items.add(file);
        }
        notifyDataSetChanged();
    }

    private String formatDate(String mtime) {
        try {
            Date date = inputFormat.parse(mtime);
            if (date != null) return headerFormat.format(date);
        } catch (Exception e) {
            return "Unknown Date";
        }
        return "Unknown Date";
    }

    public List<MediaFile> getOnlyMediaFiles() {
        List<MediaFile> mediaOnly = new ArrayList<>();
        for (Object obj : items) {
            if (obj instanceof MediaFile) mediaOnly.add((MediaFile) obj);
        }
        return mediaOnly;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_timeline_header, parent, false);
            return new HeaderViewHolder(v);
        } else {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo, parent, false);
            return new ItemViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).title.setText((String) item);
        } else {
            bindItem((ItemViewHolder) holder, (MediaFile) item, position);
        }
    }

    private void bindItem(ItemViewHolder holder, MediaFile file, int position) {
        Context context = holder.itemView.getContext();
        SharedPreferences prefs = context.getSharedPreferences("HomeServerPrefs", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("server_ip", "");
        
        String thumbEndpoint = file.getMime().startsWith("video/") ? "thumbnail/video/" : "thumbnail/";
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String thumbUrl = baseUrl + thumbEndpoint + file.getRelpath();

        Glide.with(context)
                .load(thumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .signature(new ObjectKey(file.getMtime()))
                .override(320, 320)
                .centerCrop()
                .placeholder(R.drawable.photo1)
                .into(holder.image);

        holder.videoIndicator.setVisibility(file.getMime().startsWith("video/") ? View.VISIBLE : View.GONE);
        
        holder.itemView.setOnClickListener(v -> {
            int mediaPos = 0;
            for (int i = 0; i < position; i++) {
                if (items.get(i) instanceof MediaFile) mediaPos++;
            }
            listener.onItemClick(file, mediaPos);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(int spanCount) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return getItemViewType(position) == TYPE_HEADER ? spanCount : 1;
            }
        };
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        HeaderViewHolder(View v) {
            super(v);
            title = v.findViewById(R.id.headerTitle);
        }
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        ImageView image, videoIndicator;
        ItemViewHolder(View v) {
            super(v);
            image = v.findViewById(R.id.photoImage);
            videoIndicator = v.findViewById(R.id.videoIndicator);
        }
    }
}