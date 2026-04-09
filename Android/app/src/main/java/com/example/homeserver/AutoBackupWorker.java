package com.example.homeserver;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.homeserver.data.api.RetrofitClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Response;

public class AutoBackupWorker extends Worker {

    private static final String TAG = "AutoBackupWorker";
    private static final String PREFS_NAME = "BackupPrefs";
    private static final String KEY_BACKUP_HISTORY = "synced_files";
    private static final String CAMERA_RELATIVE_PATH = "DCIM/Camera/";

    private static class MediaItem {
        final Uri uri;
        final String displayName;
        final String mimeType;
        final long dateAdded;

        MediaItem(Uri uri, String displayName, String mimeType, long dateAdded) {
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.dateAdded = dateAdded;
        }
    }

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting automatic backup scan...");

        if (!hasMediaReadPermission()) {
            Log.w(TAG, "Missing media read permission. Skipping backup.");
            return Result.success();
        }

        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> originalSet = prefs.getStringSet(KEY_BACKUP_HISTORY, new HashSet<>());

        // Create a copy to avoid modifying the original set returned by SharedPreferences
        Set<String> syncedFiles = new HashSet<>(originalSet);

        List<MediaItem> candidates = new ArrayList<>();
        candidates.addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI));
        candidates.addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI));

        // Newest first
        Collections.sort(candidates, Comparator.comparingLong((MediaItem m) -> m.dateAdded).reversed());

        int uploadCount = 0;
        boolean changed = false;
        for (MediaItem item : candidates) {
            if (isStopped()) return Result.retry();

            String key = item.uri.toString();
            if (!syncedFiles.contains(key)) {
                if (uploadItem(item)) {
                    syncedFiles.add(key);
                    uploadCount++;
                    changed = true;
                    // Limit concurrent or per-run uploads to avoid battery drain
                    if (uploadCount >= 10) break;
                }
            }
        }

        if (changed) {
            prefs.edit()
                .putStringSet(KEY_BACKUP_HISTORY, syncedFiles)
                .apply();
        }

        return Result.success();
    }

    private boolean uploadItem(MediaItem item) {
        File tempFile = null;
        try {
            String mimeType = item.mimeType != null ? item.mimeType : "image/jpeg";
            tempFile = copyToCacheFile(item.uri, item.displayName);
            MediaType mediaType = MediaType.parse(mimeType);
            RequestBody requestFile = RequestBody.create(tempFile, mediaType);
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", tempFile.getName(), requestFile);

            Response<ResponseBody> response;
            if (mimeType.startsWith("video/")) {
                response = RetrofitClient.getApiService(getApplicationContext()).uploadVideo(body, false).execute();
            } else {
                response = RetrofitClient.getApiService(getApplicationContext()).uploadPhoto("AutoBackup", body, false).execute();
            }

            return response.isSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "Upload failed: " + item.displayName, e);
            return false;
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private boolean hasMediaReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int img = ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.READ_MEDIA_IMAGES);
            int vid = ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.READ_MEDIA_VIDEO);
            return img == PackageManager.PERMISSION_GRANTED && vid == PackageManager.PERMISSION_GRANTED;
        } else {
            int read = ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.READ_EXTERNAL_STORAGE);
            return read == PackageManager.PERMISSION_GRANTED;
        }
    }

    private List<MediaItem> queryMedia(Uri collection) {
        List<MediaItem> results = new ArrayList<>();
        String[] projection = new String[] {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH
        };

        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = new String[] { CAMERA_RELATIVE_PATH + "%" };
        String sortOrder = MediaStore.MediaColumns.DATE_ADDED + " DESC";

        try (Cursor cursor = getApplicationContext().getContentResolver().query(
                collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor == null) return results;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                long date = cursor.getLong(dateCol);
                Uri uri = ContentUris.withAppendedId(collection, id);
                results.add(new MediaItem(uri, name, mime, date));
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore query failed", e);
        }

        return results;
    }

    private File copyToCacheFile(Uri uri, String displayName) throws Exception {
        String safeName = (displayName == null || displayName.trim().isEmpty()) ? "upload.bin" : displayName;
        File tempFile = new File(getApplicationContext().getCacheDir(), safeName);
        try (InputStream inputStream = getApplicationContext().getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[4 * 1024];
            int read;
            while (inputStream != null && (read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }
        return tempFile;
    }
}
