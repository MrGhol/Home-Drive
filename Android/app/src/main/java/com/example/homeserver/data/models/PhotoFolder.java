package com.example.homeserver.data.models;

import com.google.gson.annotations.SerializedName;

public class PhotoFolder {
    private String name;
    @SerializedName("file_count")
    private int fileCount;
    @SerializedName("size_bytes")
    private long sizeBytes;
    @SerializedName("size_human")
    private String sizeHuman;
    private String preview;

    // Getters
    public String getName() { return name; }
    public int getFileCount() { return fileCount; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSizeHuman() { return sizeHuman; }
    public String getPreview() { return preview; }
}