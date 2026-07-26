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

/**
 * Tiny status/help screen. Not part of the share flow — it gives the app a
 * launcher icon, explains how to use it, and lets you pre-grant Termux's
 * runtime "Run commands" permission before ever sharing a file. Built in code so
 * the app ships no layout XML.
 */
public class MainActivity extends Activity {

    private static final int REQ_RUN_COMMAND = 2;
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
                "1. Install Termux + the claude-launch script (see the repo README).\n" +
                "2. In Termux, set allow-external-apps=true in ~/.termux/termux.properties, " +
                "then FULLY close and reopen Termux.\n" +
                "3. Grant the “Run commands” permission below.\n\n" +
                "Then just share a file and pick “Open Claude Code here”.");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(Color.parseColor("#222222"));
        tv.setGravity(Gravity.START);
        root.addView(tv);

        Button grant = new Button(this);
        grant.setText("Grant Termux “Run commands” permission");
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{ShareActivity.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(grant, lp);

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
        if (requestCode == REQ_RUN_COMMAND) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this,
                        "Denied. If no dialog appeared, grant it manually in "
                                + "Settings → Apps → Open Claude Code Here → Permissions.",
                        Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        }
    }

    private void refreshStatus() {
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(ShareActivity.PERM_RUN_COMMAND)
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
