package com.example.homeserver;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.homeserver.data.api.RetrofitClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Response;

public class UploadWorker extends Worker {

    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1;
    private final NotificationManager notificationManager;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @NonNull
    @Override
    public Result doWork() {
        String uriString = getInputData().getString("uri");
        String mimeType = getInputData().getString("mimeType");
        String fileName = getInputData().getString("fileName");
        String folder = getInputData().getString("folder");

        if (uriString == null) return Result.failure();
        Uri uri = Uri.parse(uriString);

        // Show foreground notification for reliability
        setForegroundAsync(createForegroundInfo(fileName, 0));

        try {
            File file = getFileFromUri(uri, fileName);
            ProgressRequestBody requestFile = new ProgressRequestBody(file, MediaType.parse(mimeType), progress -> {
                setProgressAsync(new Data.Builder().putInt("progress", progress).build());
                notificationManager.notify(NOTIFICATION_ID, createNotification(fileName, progress));
            });

            MultipartBody.Part body = MultipartBody.Part.createFormData("file", fileName, requestFile);

            Response<ResponseBody> response;
            if (mimeType != null && mimeType.startsWith("video/")) {
                response = RetrofitClient.getApiService(getApplicationContext()).uploadVideo(body).execute();
            } else {
                response = RetrofitClient.getApiService(getApplicationContext()).uploadPhoto(folder, body).execute();
            }

            if (response.isSuccessful()) {
                showCompletionNotification(fileName, true);
                return Result.success();
            } else {
                showCompletionNotification(fileName, false);
                return Result.retry();
            }

        } catch (Exception e) {
            Log.e("UploadWorker", "Error: " + e.getMessage());
            showCompletionNotification(fileName, false);
            return Result.failure();
        }
    }

    @NonNull
    private ForegroundInfo createForegroundInfo(@NonNull String fileName, int progress) {
        return new ForegroundInfo(NOTIFICATION_ID, createNotification(fileName, progress));
    }

    @NonNull
    private android.app.Notification createNotification(@NonNull String fileName, int progress) {
        return new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentTitle("Uploading to Home Server")
                .setContentText(fileName)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setProgress(100, progress, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void showCompletionNotification(String fileName, boolean success) {
        android.app.Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentTitle(success ? "Upload Complete" : "Upload Failed")
                .setContentText(fileName)
                .setSmallIcon(success ? android.R.drawable.stat_sys_upload_done : android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build();
        notificationManager.notify(fileName.hashCode(), notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Media Uploads", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private File getFileFromUri(Uri uri, String fileName) throws Exception {
        InputStream inputStream = getApplicationContext().getContentResolver().openInputStream(uri);
        File tempFile = new File(getApplicationContext().getCacheDir(), fileName);
        try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[4 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }
        return tempFile;
    }
}