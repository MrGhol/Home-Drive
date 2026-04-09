package com.example.homeserver.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.homeserver.data.models.CachedMediaFile;

import java.util.List;

@Dao
public interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<CachedMediaFile> files);

    @Query("SELECT * FROM media_files WHERE folderPath = :folderPath ORDER BY mtime DESC")
    List<CachedMediaFile> getFilesForFolder(String folderPath);

    @Query("DELETE FROM media_files WHERE folderPath = :folderPath")
    void deleteFolderCache(String folderPath);
}