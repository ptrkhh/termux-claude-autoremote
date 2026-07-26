package com.ptrkhh.openclaudehere;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;

/**
 * Best-effort resolution of a shared {@link Uri} to a real filesystem path that
 * Termux can {@code cd} into.
 *
 * <p>This is the whole ballgame for file-explorer compatibility. Managers that
 * share a {@code file://} URI or a system SAF URI (external-storage / MediaStore
 * document) can be resolved. Managers that share through their own private
 * {@code FileProvider} expose only an opaque stream with no path — those cannot
 * be resolved, and the caller shows the "try a different file explorer" toast.
 */
final class PathResolver {

    private PathResolver() {}

    /** @return an absolute file path, or {@code null} if the Uri hides its real location. */
    static String resolve(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }

        String scheme = uri.getScheme();

        // 1. file:// — the easy case (older MiXplorer/Total Commander/Ghost Commander).
        if ("file".equalsIgnoreCase(scheme)) {
            return uri.getPath();
        }

        if (!"content".equalsIgnoreCase(scheme)) {
            return null;
        }

        // 2. System SAF document URIs (Files by Google, DocumentsUI, the system
        //    file picker). These encode the real path in the document id.
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                String fromDoc = fromDocumentUri(context, uri);
                if (!TextUtils.isEmpty(fromDoc)) {
                    return fromDoc;
                }
            }
        } catch (Exception ignored) {
            // fall through to the _data probe
        }

        // 3. Last resort: some providers still expose the legacy _data column
        //    (MediaStore-backed shares, a few third-party providers).
        String fromData = queryDataColumn(context, uri);
        if (!TextUtils.isEmpty(fromData)) {
            return fromData;
        }

        // Opaque private FileProvider — no path available.
        return null;
    }

    private static String fromDocumentUri(Context context, Uri uri) {
        String authority = uri.getAuthority();
        String docId = DocumentsContract.getDocumentId(uri);
        if (TextUtils.isEmpty(docId)) {
            return null;
        }

        if ("com.android.externalstorage.documents".equals(authority)) {
            // docId looks like "primary:Download/foo.txt" or "1A2B-3C4D:Dir/foo".
            String[] parts = docId.split(":", 2);
            String volume = parts[0];
            String relative = parts.length > 1 ? parts[1] : "";
            if ("primary".equalsIgnoreCase(volume)) {
                String base = Environment.getExternalStorageDirectory().getAbsolutePath();
                return relative.isEmpty() ? base : base + "/" + relative;
            }
            // Removable SD card / USB volume, mounted at /storage/<VOLUME>.
            return "/storage/" + volume + (relative.isEmpty() ? "" : "/" + relative);
        }

        if ("com.android.providers.downloads.documents".equals(authority)) {
            // Newer Android encodes the real path as "raw:/storage/emulated/0/...".
            if (docId.startsWith("raw:")) {
                return docId.substring(4);
            }
            // Numeric ids point into a content table with no stable path; give up
            // and let the _data probe try.
            return null;
        }

        if ("com.android.providers.media.documents".equals(authority)) {
            // docId like "image:1234" — resolve via MediaStore's _data column.
            String[] parts = docId.split(":", 2);
            if (parts.length < 2) {
                return null;
            }
            Uri contentUri;
            switch (parts[0]) {
                case "image": contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; break;
                case "video": contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; break;
                case "audio": contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; break;
                default: contentUri = MediaStore.Files.getContentUri("external"); break;
            }
            return queryDataColumn(context, contentUri, "_id=?", new String[]{parts[1]});
        }

        return null;
    }

    private static String queryDataColumn(Context context, Uri uri) {
        return queryDataColumn(context, uri, null, null);
    }

    private static String queryDataColumn(Context context, Uri uri, String selection, String[] args) {
        final String column = "_data";
        try (Cursor c = context.getContentResolver()
                .query(uri, new String[]{column}, selection, args, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(column);
                if (idx >= 0) {
                    String path = c.getString(idx);
                    if (!TextUtils.isEmpty(path) && new File(path).getParentFile() != null) {
                        return path;
                    }
                }
            }
        } catch (Exception ignored) {
            // provider doesn't expose _data
        }
        return null;
    }
}
