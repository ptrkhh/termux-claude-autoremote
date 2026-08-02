package com.ptrkhh.openclaudehere;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Invisible share-sheet target. Receives a shared file, works out the folder it
 * lives in ("here"), and opens {@link LaunchActivity} with that folder
 * preselected. Everything else — permissions, model/effort, error routing — is
 * the launch screen's job.
 */
public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri uri = extractUri(getIntent());
        if (uri == null) {
            fail("No file was shared.", null);
            return;
        }

        String path = PathResolver.resolve(this, uri);
        if (path == null) {
            fail("Couldn't find this file's folder.\nThis file explorer hides the real path — try another (e.g. Files by Google, MiXplorer in file:// mode).", uri);
            return;
        }

        File file = new File(path);
        // "here" = the directory. If the user somehow shared a directory itself,
        // use it directly; otherwise use the file's parent.
        File dir = file.isDirectory() ? file : file.getParentFile();
        if (dir == null) {
            fail("Couldn't find this file's folder.", uri);
            return;
        }

        // Hand off rather than launching here: one launch path to build and test,
        // and the user sees and can change what is about to run.
        startActivity(new Intent(this, LaunchActivity.class)
                .putExtra(LaunchActivity.EXTRA_DIR, dir.getAbsolutePath())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    /** Pulls the first usable content/file Uri out of a SEND or SEND_MULTIPLE intent. */
    private Uri extractUri(Intent intent) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return getParcelableExtraCompat(intent, Intent.EXTRA_STREAM);
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) {
                for (Uri u : uris) {
                    if (u != null) {
                        return u; // first item decides the folder
                    }
                }
            }
        }
        // Fallback: some senders put a single Uri in the data field.
        return intent.getData();
    }

    @SuppressWarnings("deprecation")
    private Uri getParcelableExtraCompat(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, Uri.class);
        }
        return intent.getParcelableExtra(key);
    }

    /**
     * Shows an error toast and finishes. When a Uri is involved, copies it to the
     * clipboard so the exact failing Uri can be reported back while testing
     * different file explorers.
     */
    private void fail(String message, Uri uri) {
        if (uri != null) {
            try {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(ClipData.newPlainText("shared-uri", uri.toString()));
                }
            } catch (Exception ignored) {
                // clipboard is a nice-to-have for diagnostics only
            }
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
}
