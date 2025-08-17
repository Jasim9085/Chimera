package com.chimera;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FileManager {

    public static class FileListing {
        public final String formattedList;
        public final int totalPages;
        public final int currentPage;
        public final String currentPath;

        FileListing(String formattedList, int totalPages, int currentPage, String currentPath) {
            this.formattedList = formattedList;
            this.totalPages = totalPages;
            this.currentPage = currentPage;
            this.currentPath = currentPath;
        }
    }

    public interface DownloadCallback {
        void onProgress(String message);
        void onChunkReady(File chunkFile, int chunkNumber, int totalChunks);
        void onComplete(String finalMessage);
        void onError(String errorMessage);
    }

    public static FileListing listFiles(Context context, Uri directoryUri, int page) {
        final int pageSize = 15;
        ContentResolver resolver = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(directoryUri));

        List<String> folders = new ArrayList<>();
        List<String> files = new ArrayList<>();
        String currentPath = getPathFromUri(context, directoryUri);

        try (Cursor c = resolver.query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String displayName = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                    String mimeType = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE));
                    String docId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    Uri itemUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        folders.add("📁 `" + displayName + "`\n_f:" + Uri.encode(itemUri.toString()) + "_");
                    } else {
                        files.add("📄 `" + displayName + "`\n_d:" + Uri.encode(itemUri.toString()) + "_");
                    }
                }
            }
        } catch (Exception e) {
            return new FileListing("Error listing files: " + e.getMessage(), 1, 1, currentPath);
        }

        Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(files, String.CASE_INSENSITIVE_ORDER);

        List<String> combinedList = new ArrayList<>(folders);
        combinedList.addAll(files);

        if (combinedList.isEmpty()) {
            return new FileListing("This directory is empty.", 1, 1, currentPath);
        }

        int totalItems = combinedList.size();
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, totalItems);

        if (start >= totalItems) {
            return new FileListing("This page is empty.", totalPages, page, currentPath);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(combinedList.get(i)).append("\n\n");
        }

        return new FileListing(sb.toString(), totalPages, page, currentPath);
    }

    public static void downloadFile(Context context, Uri fileUri, DownloadCallback callback) {
        new Thread(() -> {
            ContentResolver resolver = context.getContentResolver();
            final long maxChunkSize = 48 * 1024 * 1024; // 48MB to be safe
            String fileName = getFileNameFromUri(context, fileUri);

            try (InputStream inputStream = resolver.openInputStream(fileUri)) {
                if (inputStream == null) {
                    callback.onError("Failed to open file stream.");
                    return;
                }

                long fileSize = getFileSizeFromUri(context, fileUri);
                if (fileSize == 0) {
                     callback.onError("Could not determine file size or file is empty.");
                     return;
                }

                int totalChunks = (int) Math.ceil((double) fileSize / maxChunkSize);
                callback.onProgress("Starting download of " + fileName + " (" + formatSize(fileSize) + "). Total chunks: " + totalChunks);

                byte[] buffer = new byte[8192];
                int bytesRead;
                for (int i = 0; i < totalChunks; i++) {
                    File chunkFile = new File(context.getCacheDir(), fileName + ".part" + (i + 1));
                    long currentChunkSize = 0;
                    try (OutputStream outputStream = new java.io.FileOutputStream(chunkFile)) {
                        while (currentChunkSize < maxChunkSize && (bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            currentChunkSize += bytesRead;
                        }
                    }
                    callback.onChunkReady(chunkFile, i + 1, totalChunks);
                }
                callback.onComplete("✅ Download complete: " + fileName);

            } catch (Exception e) {
                ErrorLogger.logError(context, "FileDownload", e);
                callback.onError("Error during download: " + e.getMessage());
            }
        }).start();
    }
    
    public static boolean uploadFile(Context context, File sourceFile, Uri destinationDirUri, String desiredFileName) {
        ContentResolver resolver = context.getContentResolver();
        try {
            Uri newFileUri = DocumentsContract.createDocument(resolver, destinationDirUri, guessMimeType(desiredFileName), desiredFileName);
            if (newFileUri == null) return false;

            try (OutputStream os = resolver.openOutputStream(newFileUri);
                 InputStream is = new FileInputStream(sourceFile)) {
                if (os == null) return false;
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
            return true;
        } catch (Exception e) {
            ErrorLogger.logError(context, "FileUpload", e);
            return false;
        }
    }

    public static String getPathFromUri(Context context, Uri uri) {
        if (!DocumentsContract.isTreeUri(uri)) {
             return "Root";
        }
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
            return getFileNameFromUri(context, docUri);
        } catch(Exception e) {
            return "Unknown";
        }
    }
    
    public static String getFileNameFromUri(Context context, Uri uri) {
        String displayName = "unknown_file";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
            }
        }
        return displayName;
    }
    
    private static long getFileSizeFromUri(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE));
            }
        }
        return 0;
    }

    private static String formatSize(long size) {
        if (size < 1024) return size + " B";
        int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format("%.1f %sB", (double)size / (1L << (z*10)), " KMGTPE".charAt(z));
    }

    private static String guessMimeType(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i+1);
        }
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        return mime != null ? mime : "application/octet-stream";
    }
}