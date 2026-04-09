package com.example.homeserver.data.api;

import com.example.homeserver.data.models.MediaFile;
import com.example.homeserver.data.models.PhotoFolder;
import com.example.homeserver.data.models.ServerInfo;

import java.util.List;

import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {

    @GET("/health")
    Call<ServerInfo> getHealth();

    @GET("/folders/photos")
    Call<List<PhotoFolder>> getPhotoFolders();

    @GET("/folders/browse")
    Call<BrowseResponse> browse(@Query("path") String path);

    @PUT("/folders/photos/{old_name}")
    Call<ResponseBody> renameFolder(@Path("old_name") String oldName, @Body RenameRequest request);

    @GET("/files/photos/{folder_name}")
    Call<MediaListResponse> getPhotosInFolder(
        @Path("folder_name") String folderName,
        @Query("skip") int skip,
        @Query("limit") int limit
    );

    @GET("/files/videos")
    Call<MediaListResponse> getVideos(
        @Query("skip") int skip,
        @Query("limit") int limit
    );

    @GET("/search")
    Call<MediaListResponse> search(@Query("q") String query);

    @Multipart
    @POST("/upload/photos/{folder_name}")
    Call<ResponseBody> uploadPhoto(
        @Path("folder_name") String folderName,
        @Part MultipartBody.Part file,
        @Query("overwrite") boolean overwrite
    );

    @Multipart
    @POST("/upload/videos")
    Call<ResponseBody> uploadVideo(
        @Part MultipartBody.Part file,
        @Query("overwrite") boolean overwrite
    );

    @DELETE("/files/{file_path}")
    Call<ResponseBody> deleteFile(@Path(value = "file_path", encoded = true) String filePath);

    @DELETE("/folders/photos/{folder_name}")
    Call<ResponseBody> deleteFolder(@Path("folder_name") String folderName);

    class MediaListResponse {
        public int total;
        public List<MediaFile> files;
    }

    class BrowseResponse {
        public List<PhotoFolder> folders;
        public List<MediaFile> files;
    }

    class RenameRequest {
        public String new_name;
        public RenameRequest(String new_name) { this.new_name = new_name; }
    }
}
