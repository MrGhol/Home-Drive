package com.example.homeserver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.homeserver.data.api.ApiService;
import com.example.homeserver.data.api.RetrofitClient;
import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.databinding.FragmentUploadBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class UploadFragment extends Fragment {

    private FragmentUploadBinding binding;
    private Uri selectedFileUri;
    private String selectedMimeType;

    private final ActivityResultLauncher<Intent> pickFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    selectedMimeType = requireContext().getContentResolver().getType(selectedFileUri);
                    String fileName = getFileName(selectedFileUri);
                    binding.selectedFileName.setText("Selected: " + fileName);
                    binding.uploadButton.setEnabled(true);
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentUploadBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.selectFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            String[] mimeTypes = {"image/*", "video/*"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            pickFileLauncher.launch(intent);
        });

        binding.uploadButton.setOnClickListener(v -> checkFileConflict());
    }

    private void checkFileConflict() {
        if (selectedFileUri == null) return;
        
        String fileName = getFileName(selectedFileUri);
        String folder = selectedMimeType.startsWith("video/") ? "videos" : "photos/" + binding.folderInput.getText().toString().trim();
        if (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
        if (folder.equals("photos/")) folder = "photos/Vacation";

        final String finalFolder = folder;
        binding.uploadProgressBar.setVisibility(View.VISIBLE);
        binding.uploadButton.setEnabled(false);

        // Check if file already exists in the destination folder
        RetrofitClient.getApiService(requireContext()).browse(finalFolder).enqueue(new Callback<ApiService.BrowseResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiService.BrowseResponse> call, @NonNull Response<ApiService.BrowseResponse> response) {
                if (!isAdded()) return;
                
                boolean exists = false;
                if (response.isSuccessful() && response.body() != null) {
                    for (MediaFile file : response.body().files) {
                        if (file.getName().equalsIgnoreCase(fileName)) {
                            exists = true;
                            break;
                        }
                    }
                }

                if (exists) {
                    showConflictDialog(fileName);
                } else {
                    startUpload(fileName, false);
                }
            }

            @Override
            public void onFailure(@NonNull Call<ApiService.BrowseResponse> call, @NonNull Throwable t) {
                if (isAdded()) startUpload(fileName, false); // Proceed if check fails
            }
        });
    }

    private void showConflictDialog(String currentName) {
        String[] options = {"Rename", "Overwrite", "Skip"};
        new AlertDialog.Builder(requireContext())
                .setTitle("File Conflict")
                .setMessage("A file named '" + currentName + "' already exists. What would you like to do?")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showRenameDialog(currentName);
                    else if (which == 1) startUpload(currentName, true);
                    else {
                        binding.uploadProgressBar.setVisibility(View.GONE);
                        binding.uploadButton.setEnabled(true);
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void showRenameDialog(String oldName) {
        EditText input = new EditText(requireContext());
        input.setText(oldName);
        new AlertDialog.Builder(requireContext())
                .setTitle("Rename File")
                .setView(input)
                .setPositiveButton("Upload", (dialog, which) -> startUpload(input.getText().toString().trim(), false))
                .setNegativeButton("Cancel", (dialog, which) -> {
                    binding.uploadProgressBar.setVisibility(View.GONE);
                    binding.uploadButton.setEnabled(true);
                })
                .show();
    }

    private void startUpload(String fileName, boolean overwrite) {
        binding.uploadProgressBar.setVisibility(View.VISIBLE);
        binding.uploadProgressBar.setIndeterminate(true);
        binding.uploadButton.setEnabled(false);

        try {
            File file = getFileFromUri(selectedFileUri, fileName);
            RequestBody requestFile = RequestBody.create(MediaType.parse(selectedMimeType), file);
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", fileName, requestFile);

            Call<ResponseBody> call;
            if (selectedMimeType.startsWith("video/")) {
                call = RetrofitClient.getApiService(requireContext()).uploadVideo(body, overwrite);
            } else {
                String folder = binding.folderInput.getText().toString().trim();
                if (folder.isEmpty()) folder = "Vacation";
                call = RetrofitClient.getApiService(requireContext()).uploadPhoto(folder, body, overwrite);
            }

            call.enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                    if (isAdded() && binding != null) {
                        binding.uploadProgressBar.setVisibility(View.GONE);
                        binding.uploadButton.setEnabled(true);
                        if (response.isSuccessful()) {
                            Toast.makeText(getContext(), "Upload successful!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), "Upload failed: " + response.code(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                    if (isAdded() && binding != null) {
                        binding.uploadProgressBar.setVisibility(View.GONE);
                        binding.uploadButton.setEnabled(true);
                        Toast.makeText(getContext(), "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });

        } catch (Exception e) {
            Toast.makeText(getContext(), "Error preparing file", Toast.LENGTH_SHORT).show();
            binding.uploadProgressBar.setVisibility(View.GONE);
            binding.uploadButton.setEnabled(true);
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private File getFileFromUri(Uri uri, String fileName) throws Exception {
        InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
        File tempFile = new File(requireContext().getCacheDir(), fileName);
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
