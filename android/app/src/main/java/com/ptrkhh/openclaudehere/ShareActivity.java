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
 */
public class ShareActivity extends Activity {

    // Where the launcher is installed by install.sh (Termux $HOME/bin).
    private static final String CLAUDE_LAUNCH =
            "/data/data/com.termux/files/home/bin/claude-launch";

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    // Termux declares RUN_COMMAND as a *dangerous* (runtime) permission, so it is
    // never granted just by being listed in the manifest — it must be requested.
    static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_RUN_COMMAND = 1;

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
                && checkSelfPermission(PERM_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
            return; // continue in onRequestPermissionsResult
        }

        launchClaude(pendingDir);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_RUN_COMMAND) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchClaude(pendingDir);
            } else {
                fail("This app needs Termux's “Run commands” permission.\n"
                        + "Grant it in Settings → Apps → Open Claude Code Here → Permissions, then share again.", null);
            }
            finish();
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

    private void launchClaude(String dir) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", CLAUDE_LAUNCH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"--dir", dir});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", dir);
        // false -> Termux opens a terminal session in the foreground, which the
        // interactive Claude Code process needs.
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Claude → " + new File(dir).getName(), Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            // Permission is granted by this point, so this is Termux's own gate:
            // allow-external-apps, and it only takes effect after a full restart.
            fail("Termux refused the command.\nSet `allow-external-apps=true` in ~/.termux/termux.properties, then FULLY close Termux (swipe it away / Exit from its notification) and reopen it — `termux-reload-settings` alone isn't always enough.", null);
        } catch (Exception e) {
            fail("Couldn't reach Termux. Is it installed?", null);
        }
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
