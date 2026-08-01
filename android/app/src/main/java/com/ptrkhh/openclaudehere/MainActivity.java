package com.ptrkhh.openclaudehere;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
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
public class MainActivity extends Activity {

    private static final int REQ_RUN_COMMAND = 2;

    /** Display name, package, F-Droid page — for the three apps step 1 needs. */
    private static final String[][] TERMUX_APPS = {
            {"Termux", "com.termux", "https://f-droid.org/packages/com.termux/"},
            {"Termux:API", "com.termux.api", "https://f-droid.org/packages/com.termux.api/"},
            {"Termux:Widget", "com.termux.widget", "https://f-droid.org/packages/com.termux.widget/"},
    };

    private static final int GREEN = Color.parseColor("#2E7D32");
    private static final int RED = Color.parseColor("#C62828");
    private static final int GREY = Color.parseColor("#666666");
    private static final int INK = Color.parseColor("#222222");

    private final List<AppRow> appRows = new ArrayList<>();
    private TextView permStatus;
    private TextView checkResult;
    private Button grant;

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
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("Open Claude Code Here", 22, INK, Typeface.BOLD));
        root.addView(text(
                "Share any file from a file explorer and pick “Open Claude Code here” — "
                        + "Claude Code starts in that file’s folder.", 15, GREY, Typeface.NORMAL));

        // ---- Step 1: Termux ----
        root.addView(heading("1  Install Termux"));
        root.addView(text("All three, all from the same source — mixing F-Droid and GitHub "
                + "builds breaks the plugins. Each button opens its F-Droid page.",
                15, GREY, Typeface.NORMAL));
        for (String[] app : TERMUX_APPS) {
            final String label = app[0];
            final String url = app[2];
            TextView status = statusLine(root);
            Button get = button(root, "Get " + label + " on F-Droid", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(url, label);
                }
            });
            appRows.add(new AppRow(label, app[1], status, get));
        }

        // ---- Step 2: the one command ----
        root.addView(heading("2  Paste this into Termux"));
        root.addView(text("Installs the launcher, the homescreen shortcut and everything "
                + "this app needs. Safe to run twice.", 15, GREY, Typeface.NORMAL));

        TextView command = text(Setup.SETUP_COMMAND, 13, INK, Typeface.NORMAL);
        command.setTypeface(Typeface.MONOSPACE);
        command.setTextIsSelectable(true);
        command.setBackgroundColor(Color.parseColor("#F1F1F1"));
        command.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(command);

        button(root, "Copy & open Termux", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Setup.copySetupCommand(MainActivity.this);
                if (Setup.openTermux(MainActivity.this)) {
                    toast("Copied — long-press the terminal, tap Paste, then Enter");
                } else {
                    toast("Copied. Termux isn’t installed yet (step 1).");
                }
            }
        });
        button(root, "Copy command only", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Setup.copySetupCommand(MainActivity.this);
                toast("Copied to clipboard");
            }
        });

        // ---- Step 3: the runtime permission ----
        root.addView(heading("3  Allow this app to run it"));
        root.addView(text("Android must show you this grant dialog once — installing the "
                + "app doesn’t cover it.", 15, GREY, Typeface.NORMAL));
        permStatus = statusLine(root);
        grant = button(root, "Grant Termux “Run commands”", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{Setup.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                }
            }
        });

        // ---- Verify ----
        root.addView(heading("Check setup"));
        checkResult = text("", 15, GREY, Typeface.NORMAL);
        button(root, "Check setup", new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkResult.setTextColor(GREY);
                checkResult.setText("Asking Termux…");
                Setup.probe(MainActivity.this, new Setup.ResultListener() {
                    @Override public void onResult(int code, String detail) {
                        showCheck(code);
                    }
                });
            }
        });
        root.addView(checkResult);

        // The app is the guided installer, so it has to name the last step too —
        // otherwise someone finishes here with no way to start a session.
        root.addView(heading("Last step — the homescreen shortcut"));
        root.addView(text("Long-press your homescreen → Widgets → Termux:Widget → pick "
                + "“Claude”. That shortcut is what starts a session; this app only adds "
                + "the share-sheet entry.", 15, GREY, Typeface.NORMAL));

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
            if (!granted) {
                toast("Denied. If no dialog appeared, grant it in "
                        + "Settings → Apps → Open Claude Code Here → Permissions.");
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
        checkResult.setTextColor(good ? GREEN : RED);
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
        tv.setTextColor(good ? GREEN : RED);
    }

    private TextView heading(String s) {
        TextView tv = text(s, 17, INK, Typeface.BOLD);
        ((LinearLayout.LayoutParams) tv.getLayoutParams()).topMargin = dp(28);
        return tv;
    }

    private TextView text(String s, int sp, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, style);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView statusLine(LinearLayout parent) {
        TextView tv = text("", 15, GREY, Typeface.BOLD);
        parent.addView(tv);
        return tv;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
