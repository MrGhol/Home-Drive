package com.example.homeserver.data.models;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class MediaFile implements Serializable {
    private String name;
    private String relpath;
    @SerializedName("size_bytes")
    private long sizeBytes;
    @SerializedName("size_human")
    private String sizeHuman;
    private String mtime;
    private String mime;

    public MediaFile(String name, String relpath, long sizeBytes, String sizeHuman, String mtime, String mime) {
        this.name = name;
        this.relpath = relpath;
        this.sizeBytes = sizeBytes;
        this.sizeHuman = sizeHuman;
        this.mtime = mtime;
        this.mime = mime;
    }

    // Getters
    public String getName() { return name; }
    public String getRelpath() { return relpath; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSizeHuman() { return sizeHuman; }
    public String getMtime() { return mtime; }
    public String getMime() { return mime; }
}