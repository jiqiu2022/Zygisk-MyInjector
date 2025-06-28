package com.jiqiu.configapp;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FileUtils {
    private static final String TAG = "FileUtils";
    
    /**
     * Get real file path from URI, handling both file:// and content:// URIs
     * @param context Context
     * @param uri The URI to resolve
     * @return The real file path, or null if unable to resolve
     */
    public static String getRealPathFromUri(Context context, Uri uri) {
        if (uri == null) return null;
        
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        
        // Handle file:// URIs
        if ("file".equals(scheme)) {
            return uri.getPath();
        }
        
        // Handle content:// URIs
        if ("content".equals(scheme)) {
            // For content URIs, we need to copy the file to a temporary location
            return copyFileFromContentUri(context, uri);
        }
        
        // Try direct path extraction as fallback
        String path = uri.getPath();
        if (path != null) {
            // Some file managers return paths like /external_files/...
            // Try to resolve these to actual paths
            if (path.contains(":")) {
                String[] parts = path.split(":");
                if (parts.length == 2) {
                    String type = parts[0];
                    String relativePath = parts[1];
                    
                    // Common storage locations
                    if (type.endsWith("/primary")) {
                        return "/storage/emulated/0/" + relativePath;
                    } else if (type.contains("external")) {
                        return "/storage/emulated/0/" + relativePath;
                    }
                }
            }
            
            // Remove any file:// prefix
            if (path.startsWith("file://")) {
                path = path.substring(7);
            }
            
            // Check if the path exists
            Shell.Result result = Shell.cmd("test -f \"" + path + "\" && echo 'exists'").exec();
            if (result.isSuccess() && !result.getOut().isEmpty()) {
                return path;
            }
        }
        
        return null;
    }
    
    /**
     * Copy a file from content URI to temporary location
     * @param context Context
     * @param uri Content URI
     * @return Path to copied file, or null on failure
     */
    private static String copyFileFromContentUri(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        String fileName = getFileName(context, uri);
        
        if (fileName == null || !fileName.endsWith(".so")) {
            fileName = "temp_" + System.currentTimeMillis() + ".so";
        }
        
        // Create temp directory
        File tempDir = new File(context.getCacheDir(), "so_temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        
        File tempFile = new File(tempDir, fileName);
        
        try (InputStream inputStream = resolver.openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(tempFile)) {
            
            if (inputStream == null) {
                Log.e(TAG, "Unable to open input stream for URI: " + uri);
                return null;
            }
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            // Make file readable
            tempFile.setReadable(true, false);
            
            // First copy to /data/local/tmp as a temporary location
            String tempTargetPath = "/data/local/tmp/" + fileName;
            Shell.Result result = Shell.cmd(
                "cp \"" + tempFile.getAbsolutePath() + "\" \"" + tempTargetPath + "\"",
                "chmod 644 \"" + tempTargetPath + "\""
            ).exec();
            
            // Clean up temp file
            tempFile.delete();
            
            if (result.isSuccess()) {
                // Return the temporary path - it will be moved to the proper location by addGlobalSoFile
                return tempTargetPath;
            } else {
                Log.e(TAG, "Failed to copy file to /data/local/tmp/");
                return null;
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying file from content URI", e);
            return null;
        }
    }
    
    /**
     * Get file name from URI
     * @param context Context
     * @param uri URI to get name from
     * @return File name or null
     */
    private static String getFileName(Context context, Uri uri) {
        String fileName = null;
        
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(
                    uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file name from URI", e);
            }
        }
        
        if (fileName == null) {
            fileName = uri.getLastPathSegment();
        }
        
        return fileName;
    }
}