package com.ptrkhh.openclaudehere;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The launch screen: pick a folder, confirm model and effort, start Claude Code.
 *
 * <p>Chips render immediately from SharedPreferences so half the screen is never
 * blank; the folder list waits on one background RUN_COMMAND that gates and runs
 * {@code claude-launch --state} in a single round trip.
 */
public class LaunchActivity extends Activity {

    /** Absolute Termux-visible path to preselect. Set by ShareActivity. */
    static final String EXTRA_DIR = "com.ptrkhh.openclaudehere.EXTRA_DIR";

    private static final int REQ_RUN_COMMAND = 3;
    private static final String PREFS = "launch";
    private static final String KEY_MODEL = "model";
    private static final String KEY_EFFORT = "effort";

    private LinearLayout root;
    private LinearLayout folderList;
    private RadioGroup modelGroup;
    private RadioGroup effortGroup;
    private Button launchButton;
    private TextView banner;

    private final List<String> folders = new ArrayList<>();
    private String selectedDir;
    private boolean claudePresent = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = Views.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Views.text(this, "Start Claude Code", 22, Views.INK, Typeface.BOLD));

        banner = Views.text(this, "", 15, Views.RED, Typeface.NORMAL);
        banner.setVisibility(View.GONE);
        root.addView(banner);

        root.addView(Views.heading(this, "Working directory"));
        folderList = new LinearLayout(this);
        folderList.setOrientation(LinearLayout.VERTICAL);
        // So TalkBack announces the loading row's replacement instead of the list
        // silently filling in behind the user.
        folderList.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(folderList);

        root.addView(Views.heading(this, "Model"));
        modelGroup = radioRow();
        root.addView(modelGroup);

        root.addView(Views.heading(this, "Effort"));
        effortGroup = radioRow();
        root.addView(effortGroup);

        launchButton = Views.button(this, root, "Launch", new View.OnClickListener() {
            @Override public void onClick(View v) {
                fire();
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(root);
        setContentView(scroll);

        // Chips come from prefs, not from --state, so they are on screen before
        // any round trip. --state only supplies the *vocabulary*, reconciled below.
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        fillRadios(modelGroup, splitWords("opus sonnet haiku fable"), p.getString(KEY_MODEL, "opus"));
        fillRadios(effortGroup, splitWords("low medium high xhigh max"), p.getString(KEY_EFFORT, "high"));

        String preset = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DIR);
        if (preset != null) {
            select(preset);
        }
        refresh();
    }

    /** A horizontal RadioGroup — the default is vertical, which is not a chip row. */
    private RadioGroup radioRow() {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        return g;
    }

    private static List<String> splitWords(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.split(" ")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * RadioButtons rather than styled Buttons: they announce checked state to
     * TalkBack for free. A Button has no checked state, so a selected chip would
     * be announced identically to an unselected one — a regression against the
     * native AlertDialogs this screen replaces.
     */
    private void fillRadios(RadioGroup group, List<String> values, String selected) {
        group.removeAllViews();
        boolean any = false;
        for (String value : values) {
            RadioButton b = new RadioButton(this);
            b.setText(value);
            b.setTag(value);
            b.setMinHeight(Views.dp(this, Views.TOUCH_TARGET_DP));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    updateLaunchLabel();
                }
            });
            group.addView(b);
            if (value.equals(selected)) {
                b.setChecked(true);
                any = true;
            }
        }
        // A stored value absent from the returned vocabulary falls back to the
        // first entry rather than leaving nothing checked.
        if (!any && group.getChildCount() > 0) {
            ((RadioButton) group.getChildAt(0)).setChecked(true);
        }
    }

    private String checked(RadioGroup group) {
        int id = group.getCheckedRadioButtonId();
        if (id == View.NO_ID) {
            return "";
        }
        View v = group.findViewById(id);
        return v == null ? "" : String.valueOf(v.getTag());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);   // without this getIntent() keeps returning the old one
        String dir = intent == null ? null : intent.getStringExtra(EXTRA_DIR);
        if (dir != null) {
            select(dir);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Setup.deliver unregisters its own receiver on first result or timeout;
        // this is the backstop for an activity destroyed mid-flight.
    }

    /** Fires the gate + --state in one background round trip. */
    private void refresh() {
        setBusy("Loading…");
        Setup.state(this, new Setup.ResultListener() {
            @Override public void onResult(int code, String stdout, String detail) {
                if (isFinishing()) {
                    return;
                }
                onState(code, stdout);
            }
        });
    }

    private void onState(int code, String stdout) {
        switch (code) {
            case Setup.NO_TERMUX:
                openSetup("Termux isn’t installed — step 1.");
                return;
            case Setup.NO_PERMISSION:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{Setup.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                }
                return;
            case Setup.REFUSED:
                openSetup("Termux refused the command — finish setup, then fully close "
                        + "and reopen Termux.");
                return;
            case Setup.NONZERO:
                // gate: 1 stale, 2 absent, 126 not executable — all step 2.
                openSetup("Couldn’t read launcher state — run the step 2 command.");
                return;
            case Setup.TIMEOUT:
                // Not an automatic jump: a slow device must not be told its
                // working install is broken.
                setBusy("Termux won’t take commands yet. Run the step 2 command, then "
                        + "fully close Termux and reopen it.");
                Views.button(this, folderList, "Open setup", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(LaunchActivity.this, SetupActivity.class));
                    }
                });
                return;
            default:
                break;
        }
        applyState(stdout);
    }

    /**
     * Parses tagged lines. Required tags are models, efforts and claude: a script
     * that has --state but predates a tag emits perfectly parseable output, so
     * "unparseable" cannot detect that kind of skew — a missing required tag can.
     */
    private void applyState(String stdout) {
        List<String> recents = new ArrayList<>();
        List<String> pinned = new ArrayList<>();
        List<String> models = null;
        List<String> efforts = null;
        Boolean claude = null;

        for (String line : stdout.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 2) {
                continue;
            }
            if ("recents".equals(f[0])) {
                recents.add(f[1]);
            } else if ("pinned".equals(f[0])) {
                pinned.add(f[1]);
            } else if ("models".equals(f[0])) {
                models = splitWords(f[1]);
            } else if ("efforts".equals(f[0])) {
                efforts = splitWords(f[1]);
            } else if ("claude".equals(f[0])) {
                claude = "1".equals(f[1]);
            }
        }

        if (models == null || models.isEmpty() || efforts == null || efforts.isEmpty()
                || claude == null) {
            openSetup("Launcher is out of date — run the step 2 command.");
            return;
        }

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        fillRadios(modelGroup, models, p.getString(KEY_MODEL, models.get(0)));
        fillRadios(effortGroup, efforts, p.getString(KEY_EFFORT, efforts.get(0)));

        claudePresent = claude;
        if (!claudePresent) {
            banner.setText("Claude Code isn’t installed. In Termux:\n"
                    + "pkg install -y nodejs-lts && npm install -g @anthropic-ai/claude-code");
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }

        folders.clear();
        folders.addAll(recents);
        for (String s : pinned) {
            if (!folders.contains(s)) {
                folders.add(s);
            }
        }
        showFolders();
    }

    /** Rebuilds the folder list. Task 12 adds the Browse rows, Task 14 adds Resume. */
    void showFolders() {
        folderList.removeAllViews();
        for (final String dir : folders) {
            Views.button(this, folderList, dir, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    select(dir);
                }
            });
        }
        updateLaunchLabel();
    }

    void setBusy(String message) {
        folderList.removeAllViews();
        // Text, not a bare spinner: a spinner announces nothing.
        folderList.addView(Views.text(this, message, 15, Views.GREY, Typeface.NORMAL));
    }

    void select(String dir) {
        selectedDir = dir;
        updateLaunchLabel();
    }

    /** The button always names the full resolved target — nothing launches blind. */
    private void updateLaunchLabel() {
        boolean ready = selectedDir != null && claudePresent;
        launchButton.setEnabled(ready);
        if (selectedDir == null) {
            launchButton.setText("Pick a folder");
        } else {
            launchButton.setText("Launch  " + new File(selectedDir).getName()
                    + " · " + checked(modelGroup) + " · " + checked(effortGroup));
        }
    }

    private void fire() {
        final String dir = selectedDir;
        final String model = checked(modelGroup);
        final String effort = checked(effortGroup);
        if (dir == null || model.isEmpty() || effort.isEmpty()) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_MODEL, model).putString(KEY_EFFORT, effort).apply();

        launchButton.setEnabled(false);
        launchButton.setText("Starting…");
        Toast.makeText(this, "Claude → " + new File(dir).getName(), Toast.LENGTH_SHORT).show();

        // The activity stays alive for ERROR_WINDOW_MS. Finishing here would mean
        // every error branch below fires into a destroyed activity.
        Setup.launch(this, dir, model, effort, new Setup.ResultListener() {
            @Override public void onResult(int code, String stdout, String detail) {
                if (isFinishing()) {
                    return;
                }
                switch (code) {
                    case Setup.OK:
                    case Setup.TIMEOUT:
                        // Termux reports a foreground command only when it exits,
                        // so a timeout here is the session running fine.
                        finish();
                        break;
                    case Setup.NO_TERMUX:
                        openSetup("Termux isn’t installed — step 1.");
                        break;
                    case Setup.REFUSED:
                        openSetup("Termux refused the command — finish setup, then fully "
                                + "close and reopen Termux.");
                        break;
                    default:
                        // NONZERO foreground: bad folder, rejected model/effort.
                        Toast.makeText(LaunchActivity.this,
                                "Couldn’t start — check the folder still exists.",
                                Toast.LENGTH_LONG).show();
                        refresh();
                        break;
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_RUN_COMMAND) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refresh();
            } else {
                openSetup("Termux’s “Run commands” permission is needed — step 3.");
            }
        }
    }

    private void openSetup(String why) {
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, SetupActivity.class));
    }
}
