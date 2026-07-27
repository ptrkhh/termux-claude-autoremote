package com.ptrkhh.openclaudehere;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tiny status/help screen. Not part of the share flow — it gives the app a
 * launcher icon, explains how to use it, and provides two one-tap setup actions:
 * grant Termux's runtime "Run commands" permission, and install the launcher
 * scripts into Termux. Built in code so the app ships no layout XML.
 */
public class MainActivity extends Activity {

    private static final int REQ_RUN_COMMAND = 2;
    private static final int REQ_RUN_COMMAND_INSTALL = 3;

    // The self-contained installer, generated from scripts/build-installer.sh
    // (single source of truth) at build time and bundled as an asset. It embeds
    // both claude-launch and the Claude shortcut as base64 and writes them into
    // Termux's ~/bin and ~/.shortcuts. Same file that ships as a release asset.
    private static final String INSTALLER_ASSET = "install.sh";

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(this);
        tv.setText(
                "Open Claude Code Here\n\n" +
                "Share any file from a file explorer and choose “Open Claude Code here”. " +
                "Claude Code launches in that file’s folder.\n\n" +
                "Setup (one time):\n" +
                "1. Install Termux + Termux:API + Termux:Widget (same source), and Claude Code.\n" +
                "2. In Termux, set allow-external-apps=true in ~/.termux/termux.properties, " +
                "then FULLY close and reopen Termux.\n" +
                "3. Grant the “Run commands” permission below.\n" +
                "4. Tap “Install Launcher” to write claude-launch into Termux (or install it " +
                "yourself from the repo README).\n\n" +
                "Then share a file and pick “Open Claude Code here”, or add the Claude " +
                "widget from Termux:Widget to your homescreen.");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(Color.parseColor("#222222"));
        tv.setGravity(Gravity.START);
        root.addView(tv);

        Button grant = new Button(this);
        grant.setText("Grant Termux “Run commands” permission");
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{Termux.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(grant, lp);

        Button install = new Button(this);
        install.setText("Install Launcher into Termux");
        install.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                installLauncher();
            }
        });
        LinearLayout.LayoutParams lpi = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpi.topMargin = dp(12);
        root.addView(install, lpi);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(root);
        setContentView(scroll);

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_RUN_COMMAND) {
            if (!granted) {
                Toast.makeText(this,
                        "Denied. If no dialog appeared, grant it manually in "
                                + "Settings → Apps → Open Claude Code Here → Permissions.",
                        Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        } else if (requestCode == REQ_RUN_COMMAND_INSTALL) {
            if (granted) {
                doInstall(); // permission just landed — run the install the user asked for
            } else {
                Toast.makeText(this,
                        "Install needs Termux's “Run commands” permission.",
                        Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        }
    }

    /** "Install Launcher" tap: ensure the permission, then shell the installer. */
    private void installLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Termux.PERM_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Termux.PERM_RUN_COMMAND}, REQ_RUN_COMMAND_INSTALL);
            return; // continue in onRequestPermissionsResult
        }
        doInstall();
    }

    /**
     * Run the bundled installer inside Termux. Termux can't read this app's
     * assets/cache (separate app UID), so instead of handing it a file path we
     * run Termux's own bash with the installer text passed inline via {@code -c}.
     * background=false opens a Termux terminal so the user sees the result.
     */
    private void doInstall() {
        String script;
        try {
            script = readAsset(INSTALLER_ASSET);
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't read the bundled installer.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Termux.runCommand(this, Termux.BASH, new String[]{"-c", script}, Termux.HOME, false);
            Toast.makeText(this, "Installing launcher in Termux…", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this,
                    "Termux refused the command. Set allow-external-apps=true in "
                            + "~/.termux/termux.properties, then FULLY close and reopen Termux.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't reach Termux. Is it installed?", Toast.LENGTH_LONG).show();
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream is = getAssets().open(name);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    private void refreshStatus() {
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Termux.PERM_RUN_COMMAND)
                        == PackageManager.PERMISSION_GRANTED;
        status.setText(granted
                ? "✓ Run-commands permission granted."
                : "✗ Run-commands permission NOT granted yet.");
        status.setTextColor(granted ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
