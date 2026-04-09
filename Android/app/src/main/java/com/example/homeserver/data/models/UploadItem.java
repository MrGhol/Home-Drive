package com.example.homeserver.data.models;

import android.net.Uri;

public class UploadItem {
    public enum Status {
        QUEUED, UPLOADING, PAUSED, FAILED, COMPLETED
    }

    private final String id;
    private final Uri uri;
    private final String fileName;
    private final String mimeType;
    private final String folder;
    private int progress;
    private Status status;
    private String errorMessage;

    public UploadItem(String id, Uri uri, String fileName, String mimeType, String folder) {
        this.id = id;
        this.uri = uri;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.folder = folder;
        this.progress = 0;
        this.status = Status.QUEUED;
    }

    // Getters and Setters
    public String getId() { return id; }
    public Uri getUri() { return uri; }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public String getFolder() { return folder; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}