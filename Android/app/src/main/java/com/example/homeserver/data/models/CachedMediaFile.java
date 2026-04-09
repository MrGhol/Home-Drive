package com.example.homeserver.data.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "media_files")
public class CachedMediaFile {
    @PrimaryKey
    @NonNull
    private String relpath;
    private String name;
    private long sizeBytes;
    private String sizeHuman;
    private String mtime;
    private String mime;
    private String folderPath; // The parent folder this file belongs to

    public CachedMediaFile(@NonNull String relpath, String name, long sizeBytes, String sizeHuman, String mtime, String mime, String folderPath) {
        this.relpath = relpath;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.sizeHuman = sizeHuman;
        this.mtime = mtime;
        this.mime = mime;
        this.folderPath = folderPath;
    }

    @NonNull
    public String getRelpath() { return relpath; }
    public String getName() { return name; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSizeHuman() { return sizeHuman; }
    public String getMtime() { return mtime; }
    public String getMime() { return mime; }
    public String getFolderPath() { return folderPath; }

    public MediaFile toMediaFile() {
        // We might need to adjust MediaFile to have a constructor or use reflection if it's strictly GSON-only
        // But for now, let's assume we can map it back.
        return new MediaFile(name, relpath, sizeBytes, sizeHuman, mtime, mime);
    }

    public static CachedMediaFile fromMediaFile(MediaFile file, String folderPath) {
        return new CachedMediaFile(
                file.getRelpath(),
                file.getName(),
                file.getSizeBytes(),
                file.getSizeHuman(),
                file.getMtime(),
                file.getMime(),
                folderPath
        );
    }
}