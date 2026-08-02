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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.HorizontalScrollView;
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
    private static final String KEY_PERMISSION_ASKED = "permissionAsked";
    private static final String KEY_FIRST_CHECK = "firstCheck";
    private static final String KEY_USER_PICKED = "userPicked";
    private static final String KEY_SELECTED_DIR = "selectedDir";
    private static final String KEY_MODEL_SEL = "modelSel";
    private static final String KEY_EFFORT_SEL = "effortSel";
    private static final String KEY_RESUME_SID = "resumeSid";
    private static final String KEY_RESUME_TITLE = "resumeTitle";

    private LinearLayout folderList;
    private RadioGroup modelGroup;
    private RadioGroup effortGroup;
    private Button launchButton;
    private TextView banner;

    private final List<String> folders = new ArrayList<>();
    private String selectedDir;
    private boolean claudePresent = true;

    /** Non-null once a session is picked via Resume; suppresses model/effort —
     *  the script skips its grammar check when --resume is set, so both chip
     *  groups must read as inert while this is set. Cleared by select(dir). */
    private String resumeSid;
    private String resumeTitle;

    /** True from the moment Launch fires until the result lands. Guards against a
     *  second tap: showFolders() -> updateLaunchLabel() would otherwise re-enable
     *  the button mid-launch and start a second Claude session. */
    private boolean launching;

    /** Set in onDestroy. isFinishing() alone does not cover config-change destroys. */
    private boolean dead;

    /** Ask for the runtime permission at most once per instance. Setup.run reports
     *  NO_PERMISSION synchronously and onResume re-checks, so without this a single
     *  denial immediately triggers a second dialog — and a second denial is what
     *  makes Android record don't-ask-again permanently. */
    private boolean permissionAsked;

    /** Only the first failed check of an instance jumps to SetupActivity; later ones
     *  render in place. Otherwise Back out of Setup bounces straight back into it. */
    private boolean firstCheck = true;

    /** True once the user has tapped a chip, as opposed to a chip merely being
     *  pre-checked by fillRadios. Gates whether applyState trusts the checked chip
     *  over the persisted preference, which may hold a value this build does not
     *  hard-code. */
    private boolean userPicked;

    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String ANDROID_HOME = "/storage/emulated/0";

    /** Non-null while a Browse view is on screen. */
    private String browsingAt;

    /** True while the Resume session list owns folderList. Mirrors browsingAt: without
     *  it, refresh() wipes the list, Back exits the activity, and a share changes the
     *  selection invisibly underneath it. */
    private boolean sessionsOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
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
        root.addView(scrollRow(modelGroup));

        root.addView(Views.heading(this, "Effort"));
        effortGroup = radioRow();
        root.addView(scrollRow(effortGroup));

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
        String initialModel = p.getString(KEY_MODEL, "opus");
        String initialEffort = p.getString(KEY_EFFORT, "high");
        if (savedInstanceState != null) {
            permissionAsked = savedInstanceState.getBoolean(KEY_PERMISSION_ASKED);
            firstCheck = savedInstanceState.getBoolean(KEY_FIRST_CHECK, true);
            userPicked = savedInstanceState.getBoolean(KEY_USER_PICKED);
            selectedDir = savedInstanceState.getString(KEY_SELECTED_DIR);
            resumeSid = savedInstanceState.getString(KEY_RESUME_SID);
            resumeTitle = savedInstanceState.getString(KEY_RESUME_TITLE);
            String m = savedInstanceState.getString(KEY_MODEL_SEL);
            String e = savedInstanceState.getString(KEY_EFFORT_SEL);
            if (m != null && !m.isEmpty()) {
                initialModel = m;
            }
            if (e != null && !e.isEmpty()) {
                initialEffort = e;
            }
        }
        fillRadios(modelGroup, splitWords("opus sonnet haiku fable"), initialModel);
        fillRadios(effortGroup, splitWords("low medium high xhigh max"), initialEffort);
        // fillRadios rebuilds the RadioButtons from scratch, which would otherwise
        // silently re-enable a resumed session's chips across a rotation.
        setChipsEnabled(resumeSid == null);

        if (savedInstanceState == null) {
            String preset = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DIR);
            if (preset != null) {
                select(preset);
            }
        }
        updateLaunchLabel();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // None of this is derivable after a config change: the folder came from a
        // browse or a share, the chip may never have been written to prefs, and
        // permissionAsked is what stops a redelivered denial raising a second dialog.
        out.putBoolean(KEY_PERMISSION_ASKED, permissionAsked);
        out.putBoolean(KEY_FIRST_CHECK, firstCheck);
        out.putBoolean(KEY_USER_PICKED, userPicked);
        out.putString(KEY_SELECTED_DIR, selectedDir);
        out.putString(KEY_MODEL_SEL, checked(modelGroup));
        out.putString(KEY_EFFORT_SEL, checked(effortGroup));
        // Without these a rotation mid-resume-pick would restore selectedDir but
        // not resumeSid: the button would relabel to "Launch … · model · effort"
        // and a tap would fire Setup.launch instead of Setup.resume — exactly the
        // silent-wrong-action bug this feature exists to avoid.
        out.putString(KEY_RESUME_SID, resumeSid);
        out.putString(KEY_RESUME_TITLE, resumeTitle);
    }

    /** A horizontal RadioGroup — the default is vertical, which is not a chip row. */
    private RadioGroup radioRow() {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        return g;
    }

    /**
     * A horizontal RadioGroup overflows rather than wrapping: LinearLayout gives
     * later children an AT_MOST 0 width once the running total exceeds the parent,
     * so trailing chips measure to zero and become invisible and untappable. Five
     * effort chips exceed the usable width on a 360dp device, so the row scrolls.
     */
    private HorizontalScrollView scrollRow(RadioGroup group) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.addView(group, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
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
                    userPicked = true;
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
        if (dir == null) {
            return;
        }
        if (launching) {
            // This activity finishes when the in-flight launch succeeds, so the
            // shared folder would vanish with no launch and no message. Say so
            // rather than dropping it silently.
            Toast.makeText(this, "A session is already starting — share again once it opens.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (browsingAt != null || sessionsOpen) {
            // Leave the Browse listing or session list first: otherwise the
            // selection changes invisibly underneath it, and "Open here" would
            // overwrite the folder that was just shared.
            browsingAt = null;
            showFolders();
        }
        select(dir);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // isFinishing() is false for a config-change destroy, so it alone does not
        // stop an in-flight callback from touching a dead view hierarchy.
        dead = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also covers returning from SetupActivity after the user fixed whatever
        // sent them there — without this the screen sits on "Loading…" forever.
        // browsingAt guards a Browse view in progress, sessionsOpen a Resume session
        // list: resuming from another app or dismissing a dialog must not silently
        // replace either with the recents list.
        if (!launching && browsingAt == null && !sessionsOpen) {
            refresh();
        }
    }

    /** Fires the gate + --state in one background round trip. */
    private void refresh() {
        // A Browse view or a Resume session list owns the folder list while it is
        // up. fire()'s failure path calls refresh() unconditionally, and setBusy()
        // below would wipe the listing while browsingAt/sessionsOpen stayed set —
        // leaving the flag claiming a surface that is no longer on screen, which
        // then makes onResume skip every future refresh.
        if (browsingAt != null || sessionsOpen) {
            return;
        }
        setBusy("Loading…");
        Setup.state(this, new Setup.ResultListener() {
            @Override public void onResult(int code, String stdout, String detail) {
                if (dead || isFinishing()) {
                    return;
                }
                onState(code, stdout);
            }
        });
    }

    private void onState(int code, String stdout) {
        switch (code) {
            case Setup.NO_TERMUX:
                route("Termux isn’t installed — step 1.");
                return;
            case Setup.NO_PERMISSION:
                if (!permissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    permissionAsked = true;
                    requestPermissions(new String[]{Setup.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                } else {
                    showError("Termux’s “Run commands” permission is needed — open setup to grant it.");
                }
                return;
            case Setup.REFUSED:
                route("Termux refused the command — finish setup, then fully close "
                        + "and reopen Termux.");
                return;
            case Setup.NONZERO:
                // gate: 1 stale, 2 absent, 126 not executable — all step 2.
                route("Couldn’t read launcher state — run the step 2 command.");
                return;
            case Setup.TIMEOUT:
                // Not an automatic jump: a slow device must not be told its
                // working install is broken.
                showError("Termux won’t take commands yet. Run the step 2 command, then "
                        + "fully close Termux and reopen it.");
                return;
            default:
                break;
        }
        applyState(stdout);
    }

    /** First failure jumps to setup; later failures stay here with a way in. */
    private void route(String why) {
        if (firstCheck) {
            openSetup(why);
        } else {
            showError(why);
        }
    }

    /** Renders a failure in place, with a manual route to setup. */
    private void showError(String why) {
        setBusy(why);
        Views.button(this, folderList, "Open setup", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(LaunchActivity.this, SetupActivity.class));
            }
        });
    }

    /**
     * Renders a failure inside the Resume overlay. Deliberately never calls route():
     * reaching the Resume row already proves --state passed its gate moments ago, so
     * the "first failure jumps to setup" heuristic does not apply — and jumping would
     * leave folderList empty while sessionsOpen stayed set, stranding the screen with
     * no visible control at all.
     */
    private void sessionsError(String why) {
        setBusy(why);
        Views.button(this, folderList, "Back", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showFolders();
            }
        });
        Views.button(this, folderList, "Open setup", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(LaunchActivity.this, SetupActivity.class));
            }
        });
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
            route("Launcher is out of date — run the step 2 command.");
            return;
        }

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Prefer a chip the user actually tapped; otherwise restore from prefs, which
        // may hold a value this build does not hard-code.
        String curModel = userPicked ? checked(modelGroup) : p.getString(KEY_MODEL, models.get(0));
        String curEffort = userPicked ? checked(effortGroup) : p.getString(KEY_EFFORT, efforts.get(0));
        fillRadios(modelGroup, models, curModel);
        fillRadios(effortGroup, efforts, curEffort);
        // Same reason as the placeholder fillRadios in onCreate: rebuilding the
        // chips wipes whatever enabled state they had.
        setChipsEnabled(resumeSid == null);

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
    private void showFolders() {
        sessionsOpen = false;
        folderList.removeAllViews();
        Views.button(this, folderList, "🔁 Resume session…", new View.OnClickListener() {
            @Override public void onClick(View v) {
                loadSessions();
            }
        });
        for (final String dir : folders) {
            Views.button(this, folderList, dir, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    select(dir);
                }
            });
        }
        Views.button(this, folderList, "📁 Browse Termux", new View.OnClickListener() {
            @Override public void onClick(View v) {
                browse(TERMUX_HOME, TERMUX_HOME);
            }
        });
        Views.button(this, folderList, "📱 Browse Android", new View.OnClickListener() {
            @Override public void onClick(View v) {
                browse(ANDROID_HOME, ANDROID_HOME);
            }
        });
        updateLaunchLabel();
    }

    private void setBusy(String message) {
        folderList.removeAllViews();
        // Text, not a bare spinner: a spinner announces nothing.
        folderList.addView(Views.text(this, message, 15, Views.GREY, Typeface.NORMAL));
    }

    /**
     * --sessions spawns ~100 short-lived processes on an ARM device (hence its own
     * 12s budget in Setup), so this mirrors onState's full error routing rather
     * than the shorter brief version: a stale RUN_COMMAND grant or a missing
     * Termux install can surface here exactly as it can from --state, and folding
     * those into "couldn't load, try again" would hide a fixable setup problem
     * behind a retry loop that can never succeed.
     */
    private void loadSessions() {
        sessionsOpen = true;
        setBusy("Loading sessions…");
        Setup.sessions(this, new Setup.ResultListener() {
            @Override public void onResult(int code, String stdout, String detail) {
                if (dead || isFinishing()) {
                    return;
                }
                if (!sessionsOpen) {
                    return;   // the user has already left the session list
                }
                switch (code) {
                    case Setup.OK:
                        showSessions(stdout);
                        return;
                    case Setup.NO_TERMUX:
                        sessionsError("Termux isn’t installed — step 1.");
                        return;
                    case Setup.NO_PERMISSION:
                        if (!permissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            permissionAsked = true;
                            requestPermissions(new String[]{Setup.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                        } else {
                            showError("Termux’s “Run commands” permission is needed — open setup to grant it.");
                        }
                        return;
                    case Setup.REFUSED:
                        sessionsError("Termux refused the command — finish setup, then fully close "
                                + "and reopen Termux.");
                        return;
                    case Setup.NONZERO:
                        sessionsError(detail == null || detail.isEmpty()
                                ? "Launcher is out of date — run the step 2 command."
                                : detail.trim());
                        return;
                    default:
                        // TIMEOUT: not necessarily version skew — a slow device or a
                        // transient hiccup among ~100 spawned processes. Offer a
                        // retry in place rather than routing to setup.
                        sessionsError("Couldn’t load sessions — try again.");
                        return;
                }
            }
        });
    }

    /**
     * Rows are 7 columns: sid, cwd, folder, branch, when, title, summary.
     *
     * <p>split("\t", -1) is required, not stylistic: Java's split discards
     * trailing empty fields, and `summary` is empty whenever the script's
     * stitch3 produces nothing — ordinary input, no skew. Without the -1 those
     * rows arrive at length 6 and indexing throws.
     *
     * <p>Zero lines is emptiness. One or more lines of which none conforms is
     * skew, routed the same way as the other launcher-out-of-date cases so a
     * repeat offender renders in place instead of bouncing back into setup.
     * Mixed arity is neither: conforming rows render, the rest are dropped
     * silently, because a malformed row means the script's sanitizer failed —
     * a bug the user cannot act on.
     */
    private void showSessions(String stdout) {
        folderList.removeAllViews();
        int seen = 0;
        int kept = 0;
        for (String line : stdout.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            seen++;
            final String[] f = line.split("\t", -1);
            if (f.length != 7) {
                continue;
            }
            kept++;
            String label = f[2]
                    + (f[3].isEmpty() ? "" : " (" + f[3] + ")")
                    + (f[4].isEmpty() ? "" : " · " + f[4])
                    + "\n" + (f[5].isEmpty() ? "(untitled)" : f[5])
                    + (f[6].isEmpty() ? "" : "\n  ↳ " + f[6]);
            Button b = Views.button(this, folderList, label, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pickSession(f[0], f[1], f[5]);
                }
            });
            b.setMaxLines(4);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        }
        if (seen == 0) {
            folderList.addView(Views.text(this, "No sessions to resume", 15, Views.GREY,
                    Typeface.NORMAL));
        } else if (kept == 0) {
            sessionsError("Launcher is out of date — run the step 2 command.");
            return;
        }
        Views.button(this, folderList, "Back", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showFolders();
            }
        });
    }

    private void pickSession(String sid, String cwd, String title) {
        resumeSid = sid;
        resumeTitle = title.isEmpty() ? "(untitled)" : title;
        selectedDir = cwd;
        setChipsEnabled(false);
        showFolders();
    }

    /**
     * A resumed session carries its own model and effort — the script ignores
     * both when --resume is set. Leaving the chips live would let someone set an
     * effort, hit Launch, and reasonably believe it applied.
     */
    private void setChipsEnabled(boolean enabled) {
        for (int i = 0; i < modelGroup.getChildCount(); i++) {
            modelGroup.getChildAt(i).setEnabled(enabled);
        }
        for (int i = 0; i < effortGroup.getChildCount(); i++) {
            effortGroup.getChildAt(i).setEnabled(enabled);
        }
    }

    private void select(String dir) {
        selectedDir = dir;
        // Picking a folder means starting fresh — a resume choice from a moment
        // ago must not silently survive underneath a new selection.
        resumeSid = null;
        resumeTitle = null;
        setChipsEnabled(true);
        updateLaunchLabel();
    }

    /**
     * One --ls round trip per level. Upward navigation is client-side: the script
     * has no go_up, and clamping to the root here is presentation, not security —
     * the app shares Termux's UID either way.
     */
    private void browse(final String dir, final String root) {
        browsingAt = dir;
        setBusy("Reading " + dir + "…");
        Setup.ls(this, dir, new Setup.ResultListener() {
            @Override public void onResult(int code, String stdout, String detail) {
                if (dead || isFinishing() || !dir.equals(browsingAt)) {
                    return;   // a newer browse superseded this one
                }
                if (code != Setup.OK) {
                    String msg = detail == null || detail.isEmpty()
                            ? "Couldn’t read that folder."
                            : detail.trim();
                    Toast.makeText(LaunchActivity.this, msg, Toast.LENGTH_SHORT).show();
                    browsingAt = null;
                    showFolders();
                    return;
                }
                showBrowse(dir, root, stdout);
            }
        });
    }

    private void showBrowse(final String dir, final String root, String stdout) {
        folderList.removeAllViews();
        folderList.addView(Views.text(this, dir, 13, Views.GREY, Typeface.NORMAL));

        Views.button(this, folderList, "✅ Open here", new View.OnClickListener() {
            @Override public void onClick(View v) {
                browsingAt = null;
                select(dir);
                showFolders();
            }
        });
        if (!dir.equals(root)) {
            Views.button(this, folderList, "⬆ ..", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    browse(parentOf(dir, root), root);
                }
            });
        }

        boolean any = false;
        for (String name : stdout.split("\n")) {
            if (name.isEmpty()) {
                continue;
            }
            any = true;
            final String child = dir + "/" + name;
            Views.button(this, folderList, name, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    browse(child, root);
                }
            });
        }
        if (!any) {
            folderList.addView(Views.text(this, "no subfolders", 15, Views.GREY, Typeface.NORMAL));
        }
    }

    /** Parent of dir, never above root. */
    private static String parentOf(String dir, String root) {
        int cut = dir.lastIndexOf('/');
        if (cut <= 0) {
            return root;
        }
        String parent = dir.substring(0, cut);
        return parent.startsWith(root) ? parent : root;
    }

    @Override
    public void onBackPressed() {
        if (sessionsOpen) {
            showFolders();
            return;
        }
        if (browsingAt != null) {
            browsingAt = null;
            showFolders();
            return;
        }
        super.onBackPressed();
    }

    /** The button always names the full resolved target — nothing launches blind. */
    private void updateLaunchLabel() {
        if (launching) {
            return;
        }
        boolean ready = selectedDir != null && claudePresent;
        launchButton.setEnabled(ready);
        if (resumeSid != null) {
            launchButton.setText("Resume: " + resumeTitle);
        } else if (selectedDir == null) {
            launchButton.setText("Pick a folder");
        } else {
            launchButton.setText("Launch  " + new File(selectedDir).getName()
                    + " · " + checked(modelGroup) + " · " + checked(effortGroup));
        }
    }

    private void fire() {
        final String dir = selectedDir;
        if (dir == null) {
            return;
        }
        final String model = checked(modelGroup);
        final String effort = checked(effortGroup);
        // A resume carries no model/effort of its own — the script skips its
        // grammar check when --resume is set — so the chip-emptiness guard below
        // only applies to a plain launch.
        if (resumeSid == null && (model.isEmpty() || effort.isEmpty())) {
            return;
        }
        if (resumeSid == null) {
            // The user chose neither model nor effort on a resume; writing
            // whatever the (disabled, stale) chips happen to read would corrupt
            // the preference used to pre-select chips on the next plain launch.
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_MODEL, model).putString(KEY_EFFORT, effort).apply();
        }

        launching = true;
        launchButton.setEnabled(false);
        launchButton.setText("Starting…");
        Toast.makeText(this, "Claude → " + new File(dir).getName(), Toast.LENGTH_SHORT).show();

        // The activity stays alive for ERROR_WINDOW_MS. Finishing here would mean
        // every error branch below fires into a destroyed activity.
        Setup.ResultListener done = new Setup.ResultListener() {
            @Override public void onResult(int code, String stdout, String detail) {
                if (dead || isFinishing()) {
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
                        launching = false;
                        updateLaunchLabel();
                        openSetup("Termux isn’t installed — step 1.");
                        break;
                    case Setup.REFUSED:
                        launching = false;
                        updateLaunchLabel();
                        openSetup("Termux refused the command — finish setup, then fully "
                                + "close and reopen Termux.");
                        break;
                    case Setup.NO_PERMISSION:
                        launching = false;
                        updateLaunchLabel();
                        if (!permissionAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            permissionAsked = true;
                            requestPermissions(new String[]{Setup.PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
                        } else {
                            showError("Termux’s “Run commands” permission is needed — open setup to grant it.");
                        }
                        break;
                    default:
                        // NONZERO foreground: bad folder, rejected model/effort, or
                        // (on resume) a session id the script no longer recognizes.
                        // Drop back to a clean pick rather than leaving a resume
                        // selection pointed at whatever just failed.
                        launching = false;
                        selectedDir = null;
                        resumeSid = null;
                        resumeTitle = null;
                        setChipsEnabled(true);
                        updateLaunchLabel();
                        String msg = detail == null || detail.isEmpty()
                                ? "Couldn’t start — check the folder still exists."
                                : detail.trim();
                        Toast.makeText(LaunchActivity.this, msg, Toast.LENGTH_LONG).show();
                        refresh();
                        break;
                }
            }
        };
        if (resumeSid != null) {
            Setup.resume(this, dir, resumeSid, done);
        } else {
            Setup.launch(this, dir, model, effort, done);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_RUN_COMMAND) {
            return;
        }
        if (grantResults.length == 0) {
            // Cancelled, not denied — Android does not count this toward
            // don't-ask-again, so neither should we.
            permissionAsked = false;
            return;
        }
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            showError("Termux’s “Run commands” permission is needed — open setup to grant it.");
        }
        // Granted needs no action here: onResume re-runs refresh().
    }

    private void openSetup(String why) {
        // Any jump to setup — automatic or from a failed launch — counts as the
        // instance's one free jump.
        firstCheck = false;
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, SetupActivity.class));
    }
}
