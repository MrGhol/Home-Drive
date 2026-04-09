package com.example.homeserver;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.homeserver.data.api.RetrofitClient;

import java.io.File;
import java.util.HashSet;
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

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting automatic backup scan...");
        
        Set<String> originalSet = getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_BACKUP_HISTORY, new HashSet<>());
        
        // Create a copy to avoid modifying the original set returned by SharedPreferences
        Set<String> syncedFiles = new HashSet<>(originalSet);

        // Scan Camera folder
        File dcim = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
        if (!dcim.exists()) return Result.success();

        File[] files = dcim.listFiles();
        if (files == null) return Result.success();

        int uploadCount = 0;
        boolean changed = false;
        for (File file : files) {
            if (isStopped()) return Result.retry();
            
            if (file.isFile() && !syncedFiles.contains(file.getAbsolutePath())) {
                if (uploadFile(file)) {
                    syncedFiles.add(file.getAbsolutePath());
                    uploadCount++;
                    changed = true;
                    // Limit concurrent or per-run uploads to avoid battery drain
                    if (uploadCount >= 10) break; 
                }
            }
        }

        if (changed) {
            // Save history
            getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_BACKUP_HISTORY, syncedFiles)
                    .apply();
        }

        return Result.success();
    }

    private boolean uploadFile(File file) {
        try {
            String mimeType = file.getName().endsWith(".mp4") ? "video/mp4" : "image/jpeg";
            MediaType mediaType = MediaType.parse(mimeType);
            RequestBody requestFile = RequestBody.create(file, mediaType);
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", file.getName(), requestFile);

            Response<ResponseBody> response;
            if (mimeType.startsWith("video/")) {
                response = RetrofitClient.getApiService(getApplicationContext()).uploadVideo(body).execute();
            } else {
                response = RetrofitClient.getApiService(getApplicationContext()).uploadPhoto("AutoBackup", body).execute();
            }

            return response.isSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "Upload failed: " + file.getName(), e);
            return false;
        }
    }
}