package com.example.homeserver;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

public class ProgressRequestBody extends RequestBody {

    private final File file;
    private final MediaType contentType;
    private final UploadCallback listener;

    private static final int DEFAULT_BUFFER_SIZE = 4096;

    public interface UploadCallback {
        void onProgressUpdate(int percentage);
    }

    public ProgressRequestBody(File file, MediaType contentType, UploadCallback listener) {
        this.file = file;
        this.contentType = contentType;
        this.listener = listener;
    }

    @Nullable
    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() throws IOException {
        return file.length();
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        long fileLength = file.length();
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        FileInputStream in = new FileInputStream(file);
        long uploaded = 0;

        try {
            int read;
            Handler handler = new Handler(Looper.getMainLooper());
            while ((read = in.read(buffer)) != -1) {
                // Update progress on UI thread
                uploaded += read;
                int progress = (int) (100 * uploaded / fileLength);
                handler.post(() -> listener.onProgressUpdate(progress));

                sink.write(buffer, 0, read);
            }
        } finally {
            in.close();
        }
    }
}