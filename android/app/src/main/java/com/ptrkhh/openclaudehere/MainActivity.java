package com.ptrkhh.openclaudehere;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Tiny status/help screen. Not part of the share flow — it just gives the app a
 * launcher icon and explains how to use it. Built in code so the app ships no
 * layout XML.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText(
                "Open Claude Code Here\n\n" +
                "Share any file from a file explorer and choose “Open Claude Code here”. " +
                "Claude Code launches in that file’s folder.\n\n" +
                "Setup (one time):\n" +
                "1. Install Termux + the claude-launch script (see the repo README).\n" +
                "2. In Termux, set allow-external-apps=true in ~/.termux/termux.properties, " +
                "then run: termux-reload-settings\n" +
                "3. Grant this app the Termux “Run commands” permission when asked.\n\n" +
                "Then just share a file and pick “Open Claude Code here”.");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(Color.parseColor("#222222"));
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        tv.setPadding(pad, pad, pad, pad);
        tv.setGravity(Gravity.START);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(tv);
        setContentView(scroll);
    }
}
