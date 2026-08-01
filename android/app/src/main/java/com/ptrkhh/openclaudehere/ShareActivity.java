package com.ptrkhh.openclaudehere;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Invisible share-sheet target. Receives a shared file, works out the folder it
 * lives in ("here"), and asks Termux to run {@code ~/bin/claude-launch --dir
 * <folder>} — which then shows the model/effort pickers and starts Claude Code
 * in Remote Control mode.
 *
 * <p>If Termux rejects the hand-off, this opens the setup screen rather than
 * failing with a toast the user can't act on.
 */
public class ShareActivity extends Activity {

    private static final int REQ_RUN_COMMAND = 1;

    /** Termux reports refusals within a second; success not until Claude exits. */
    private static final long ERROR_WINDOW_MS = 3000;

    // Resolved between onCreate and the permission callback.
    private String pendingDir;

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

        pendingDir = dir.getAbsolutePath();

        // Ask for Termux's Run-commands permission the first time; the system
        // shows the grant dialog over this (invisible) activity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Setup.PERM_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Setup.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
            return; // continue in onRequestPermissionsResult
        }

        launchClaude(pendingDir);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_RUN_COMMAND) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchClaude(pendingDir);
            } else {
                openSetup("Termux’s “Run commands” permission is needed — step 3.");
            }
        }
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
     * Hands the folder to Termux. Stays alive (invisibly) just long enough to
     * catch a rejection: a timeout here means the session started fine, since
     * Termux only reports a foreground command's result once it exits.
     */
    private void launchClaude(final String dir) {
        Toast.makeText(this, "Claude → " + new File(dir).getName(), Toast.LENGTH_SHORT).show();
        Setup.launchClaude(this, dir, ERROR_WINDOW_MS, new Setup.ResultListener() {
            @Override public void onResult(int code, String detail) {
                switch (code) {
                    case Setup.NO_TERMUX:
                        openSetup("Termux isn’t installed — step 1.");
                        break;
                    case Setup.NO_PERMISSION:
                        openSetup("Termux’s “Run commands” permission is needed — step 3.");
                        break;
                    case Setup.REFUSED:
                        // Termux's own gate: allow-external-apps, which only
                        // takes effect after a full restart of Termux.
                        openSetup("Termux refused the command — finish setup, then fully "
                                + "close and reopen Termux.");
                        break;
                    default: // OK / NONZERO / TIMEOUT — the session is on its way
                        finish();
                        break;
                }
            }
        });
    }

    /** Shows why the hand-off failed and lands the user on the screen that fixes it. */
    private void openSetup(String why) {
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
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
