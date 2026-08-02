package com.ptrkhh.openclaudehere;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Setup screen: three steps, each showing its own live status, plus a "Check
 * setup" button that says exactly which one is missing instead of leaving the
 * user to share a file and guess.
 *
 * <p>Step 2 is a copy-paste because it has to be — see {@link Setup} for why
 * this app can't run its own setup command. "Copy &amp; open Termux" gets it as
 * close to one tap as Android allows: paste, Enter.
 *
 * <p>Built in code so the app ships no layout XML.
 */
public class SetupActivity extends Activity {

    private static final int REQ_RUN_COMMAND = 2;

    /** Display name, package, F-Droid page — the one app this setup still needs. */
    private static final String[][] TERMUX_APPS = {
            {"Termux", "com.termux", "https://f-droid.org/packages/com.termux/"},
    };

    private final List<AppRow> appRows = new ArrayList<>();
    private TextView permStatus;
    private TextView checkResult;
    private Button grant;

    /** True once Android has recorded don't-ask-again for RUN_COMMAND. */
    private boolean permanentlyDenied;

    /** One installable Termux app: its status line and its "get it" button. */
    private static final class AppRow {
        final String label;
        final String pkg;
        final TextView status;
        final Button get;

        AppRow(String label, String pkg, TextView status, Button get) {
            this.label = label;
            this.pkg = pkg;
            this.status = status;
            this.get = get;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = Views.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Views.text(this, "Open Claude Code Here", 22, Views.INK, Typeface.BOLD));
        root.addView(Views.text(this,
                "Share any file from a file explorer and pick “Open Claude Code here” — "
                        + "Claude Code starts in that file’s folder.", 15, Views.GREY, Typeface.NORMAL));

        // ---- Step 1: Termux ----
        root.addView(Views.heading(this, "1  Install Termux"));
        root.addView(Views.text(this, "Each button opens its F-Droid page.",
                15, Views.GREY, Typeface.NORMAL));
        for (String[] app : TERMUX_APPS) {
            final String label = app[0];
            final String url = app[2];
            TextView status = statusLine(root);
            Button get = Views.button(this, root, "Get " + label + " on F-Droid", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(url, label);
                }
            });
            appRows.add(new AppRow(label, app[1], status, get));
        }

        // ---- Step 2: the one command ----
        root.addView(Views.heading(this, "2  Paste this into Termux"));
        root.addView(Views.text(this, "Installs the launcher and everything this app needs to "
                + "drive it. Safe to run twice — that is also how you update.", 15,
                Views.GREY, Typeface.NORMAL));

        TextView command = Views.text(this, Setup.SETUP_COMMAND, 13, Views.INK, Typeface.NORMAL);
        command.setTypeface(Typeface.MONOSPACE);
        command.setTextIsSelectable(true);
        command.setBackgroundColor(Color.parseColor("#F1F1F1"));
        command.setPadding(Views.dp(this, 12), Views.dp(this, 12), Views.dp(this, 12), Views.dp(this, 12));
        root.addView(command);

        Views.button(this, root, "Copy & open Termux", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Setup.copySetupCommand(SetupActivity.this);
                if (Setup.openTermux(SetupActivity.this)) {
                    toast("Copied — long-press the terminal, tap Paste, then Enter");
                } else {
                    toast("Copied. Termux isn’t installed yet (step 1).");
                }
            }
        });
        Views.button(this, root, "Copy command only", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Setup.copySetupCommand(SetupActivity.this);
                toast("Copied to clipboard");
            }
        });

        // ---- Step 3: the runtime permission ----
        root.addView(Views.heading(this, "3  Allow this app to run it"));
        root.addView(Views.text(this, "Android must show you this grant dialog once — installing the "
                + "app doesn’t cover it.", 15, Views.GREY, Typeface.NORMAL));
        permStatus = statusLine(root);
        grant = Views.button(this, root, "Grant Termux “Run commands”", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    return;
                }
                // Once Android records don't-ask-again, requestPermissions is a
                // no-op forever — the old unconditional call left this button
                // doing nothing visible with no way out.
                if (permanentlyDenied) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null)));
                } else {
                    requestPermissions(new String[]{Setup.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                }
            }
        });

        // ---- Verify ----
        root.addView(Views.heading(this, "Check setup"));
        checkResult = Views.text(this, "", 15, Views.GREY, Typeface.NORMAL);
        Views.button(this, root, "Check setup", new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkResult.setTextColor(Views.GREY);
                checkResult.setText("Asking Termux…");
                Setup.state(SetupActivity.this, new Setup.ResultListener() {
                    @Override public void onResult(int code, String stdout, String detail) {
                        showCheck(code);
                    }
                });
            }
        });
        root.addView(checkResult);

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
        if (requestCode == REQ_RUN_COMMAND) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // False *after a denial* means don't-ask-again. (It is also false
                // before the first request, which is why this is only read here.)
                permanentlyDenied = !shouldShowRequestPermissionRationale(Setup.PERM_RUN_COMMAND);
            }
            if (!granted) {
                toast(permanentlyDenied
                        ? "Denied permanently. Open app settings → Permissions to grant it."
                        : "Denied. Tap the button again to retry.");
            }
            refreshStatus();
        }
    }

    private void refreshStatus() {
        for (AppRow row : appRows) {
            boolean installed = Setup.installed(this, row.pkg);
            mark(row.status, installed, row.label + " installed", row.label + " not found");
            row.get.setVisibility(installed ? View.GONE : View.VISIBLE);
        }

        boolean granted = Setup.permissionGranted(this);
        mark(permStatus, granted, "Permission granted", "Permission not granted yet");
        grant.setVisibility(granted ? View.GONE : View.VISIBLE);
        grant.setText(permanentlyDenied ? "Open app settings" : "Grant Termux “Run commands”");
    }

    private void showCheck(int code) {
        String msg;
        boolean good = code == Setup.OK;
        switch (code) {
            case Setup.OK:
                msg = "✓ All set — share a file and pick “Open Claude Code here”.";
                break;
            case Setup.NO_TERMUX:
                msg = "✗ Termux isn’t installed (step 1).";
                break;
            case Setup.NO_PERMISSION:
                msg = "✗ Permission not granted (step 3).";
                break;
            case Setup.NONZERO:
                msg = "✗ Termux answered, but claude-launch isn’t installed — run the "
                        + "step 2 command.";
                break;
            default: // REFUSED / TIMEOUT
                msg = "✗ Termux won’t take commands yet. Run the step 2 command, then "
                        + "fully close Termux (swipe it from Recents) and reopen it.";
                break;
        }
        checkResult.setTextColor(good ? Views.GREEN : Views.RED);
        checkResult.setText(msg);
    }

    /** Opens an F-Droid page. A phone with no browser shouldn't crash the screen. */
    private void open(String url, String label) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("No app can open that link. Search F-Droid for " + label + ".");
        }
    }

    // ---- tiny view helpers ----

    private void mark(TextView tv, boolean good, String yes, String no) {
        tv.setText(good ? "✓ " + yes : "✗ " + no);
        tv.setTextColor(good ? Views.GREEN : Views.RED);
    }

    private TextView statusLine(LinearLayout parent) {
        TextView tv = Views.text(this, "", 15, Views.GREY, Typeface.BOLD);
        parent.addView(tv);
        return tv;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
