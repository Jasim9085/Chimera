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

    public static class FileItem {
        public final String name;
        public final String type;
        public final String uri;

        FileItem(String name, String type, String uri) {
            this.name = name;
            this.type = type;
            this.uri = uri;
        }
    }

    public static class FileListing {
        public final List<FileItem> items;
        public final String currentPath;
        public final String parentUri;
        public final String error;

        FileListing(List<FileItem> items, String currentPath, String parentUri, String error) {
            this.items = items;
            this.currentPath = currentPath;
            this.parentUri = parentUri;
            this.error = error;
        }
    }

    public static FileListing listFiles(Context context, Uri directoryUri) {
        ContentResolver resolver = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(directoryUri));

        List<FileItem> folders = new ArrayList<>();
        List<FileItem> files = new ArrayList<>();
        String currentPath = getPathFromUri(context, directoryUri);
        Uri parentUri = getParentUri(directoryUri);

        try (Cursor c = resolver.query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String docId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    String displayName = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                    String mimeType = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE));
                    Uri itemUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        folders.add(new FileItem(displayName, "folder", itemUri.toString()));
                    } else {
                        files.add(new FileItem(displayName, "file", itemUri.toString()));
                    }
                }
            }
        } catch (Exception e) {
            return new FileListing(Collections.emptyList(), currentPath, null, "Error: " + e.getMessage());
        }

        Collections.sort(folders, (a, b) -> a.name.compareToIgnoreCase(b.name));
        Collections.sort(files, (a, b) -> a.name.compareToIgnoreCase(b.name));

        List<FileItem> combinedList = new ArrayList<>(folders);
        combinedList.addAll(files);

        return new FileListing(combinedList, currentPath, parentUri != null ? parentUri.toString() : null, null);
    }

    public static boolean deleteFile(Context context, Uri fileUri) {
        try {
            return DocumentsContract.deleteDocument(context.getContentResolver(), fileUri);
        } catch (Exception e) {
            ErrorLogger.logError(context, "FileDelete", e);
            return false;
        }
    }

    private static Uri getParentUri(Uri folderUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(folderUri);
            String[] parts = treeId.split(":");
            if (parts.length < 2 || parts[1].isEmpty()) {
                return null;
            }

            String path = parts[1];
            int lastSeparator = path.lastIndexOf(File.separator);

            if (lastSeparator > 0) {
                String parentPath = path.substring(0, lastSeparator);
                String parentDocId = parts[0] + ":" + parentPath;
                return DocumentsContract.buildDocumentUriUsingTree(folderUri, parentDocId);
            } else if (lastSeparator == 0) {
                String parentDocId = parts[0] + ":";
                return DocumentsContract.buildDocumentUriUsingTree(folderUri, parentDocId);
            }
        } catch (Exception e) {
            // Failsafe
        }
        return null;
    }

    public interface DownloadCallback { void onProgress(String message); void onChunkReady(File chunkFile, int chunkNumber, int totalChunks); void onComplete(String finalMessage); void onError(String errorMessage); }
    public static void downloadFile(Context context, Uri fileUri, DownloadCallback callback) { new Thread(() -> { ContentResolver resolver = context.getContentResolver(); final long maxChunkSize = 48 * 1024 * 1024; String fileName = getFileNameFromUri(context, fileUri); try (InputStream inputStream = resolver.openInputStream(fileUri)) { if (inputStream == null) { callback.onError("Failed to open file stream."); return; } long fileSize = getFileSizeFromUri(context, fileUri); if (fileSize <= 0) { callback.onError("Cannot download empty file or determine its size."); return; } int totalChunks = (int) Math.ceil((double) fileSize / maxChunkSize); callback.onProgress("Starting download of " + fileName + " (" + formatSize(fileSize) + "). Total chunks: " + totalChunks); byte[] buffer = new byte[8192]; int bytesRead; for (int i = 0; i < totalChunks; i++) { File chunkFile = new File(context.getCacheDir(), fileName + ".part" + (i + 1)); long currentChunkSize = 0; try (OutputStream outputStream = new java.io.FileOutputStream(chunkFile)) { while (currentChunkSize < maxChunkSize && (bytesRead = inputStream.read(buffer)) != -1) { outputStream.write(buffer, 0, bytesRead); currentChunkSize += bytesRead; } } callback.onChunkReady(chunkFile, i + 1, totalChunks); } callback.onComplete("✅ Download complete: " + fileName); } catch (Exception e) { ErrorLogger.logError(context, "FileDownload", e); callback.onError("Error during download: " + e.getMessage()); } }).start(); }
    public static boolean uploadFile(Context context, File sourceFile, Uri destinationDirUri, String desiredFileName) { ContentResolver resolver = context.getContentResolver(); try { Uri newFileUri = DocumentsContract.createDocument(resolver, destinationDirUri, guessMimeType(desiredFileName), desiredFileName); if (newFileUri == null) return false; try (OutputStream os = resolver.openOutputStream(newFileUri); InputStream is = new FileInputStream(sourceFile)) { if (os == null) return false; byte[] buffer = new byte[8192]; int bytesRead; while ((bytesRead = is.read(buffer)) != -1) { os.write(buffer, 0, bytesRead); } } return true; } catch (Exception e) { ErrorLogger.logError(context, "FileUpload", e); return false; } }
    private static String formatSize(long size) { if (size < 1024) return size + " B"; int z = (63 - Long.numberOfLeadingZeros(size)) / 10; return String.format("%.1f %sB", (double)size / (1L << (z*10)), " KMGTPE".charAt(z)); }
    private static String guessMimeType(String fileName) { String extension = ""; int i = fileName.lastIndexOf('.'); if (i > 0) { extension = fileName.substring(i+1); } String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase()); return mime != null ? mime : "application/octet-stream"; }
    public static String getFileNameFromUri(Context context, Uri uri) { String displayName = "unknown_file"; try (Cursor cursor = context.getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) { if (cursor != null && cursor.moveToFirst()) { displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)); } } return displayName; }
    private static long getFileSizeFromUri(Context context, Uri uri) { try (Cursor cursor = context.getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) { if (cursor != null && cursor.moveToFirst()) { return cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)); } } return 0; }
    public static String getPathFromUri(Context context, Uri uri) { if (!DocumentsContract.isTreeUri(uri)) return "/"; String docId = DocumentsContract.getTreeDocumentId(uri); final String[] split = docId.split(":"); if (split.length > 1 && split[1].length() > 0) { return "/" + split[1]; } else { return "/"; } }
}