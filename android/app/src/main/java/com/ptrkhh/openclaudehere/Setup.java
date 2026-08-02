package com.ptrkhh.openclaudehere;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * Everything this app knows about Termux: where the launcher lives, how to ask
 * Termux to run it, and how to tell — precisely — which setup step is missing.
 *
 * <p>Note the chicken-and-egg that shapes the whole UX: running a command in
 * Termux from another app needs {@code RUN_COMMAND}, which Termux itself gates
 * behind {@code allow-external-apps=true}. So this app cannot bootstrap its own
 * setup; the one-liner below has to be pasted into Termux once by hand.
 */
final class Setup {

    private Setup() {}

    static final String TERMUX_PACKAGE = "com.termux";
    static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    // Termux declares RUN_COMMAND as a *dangerous* (runtime) permission, so it
    // is never granted just by being listed in the manifest.
    static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";

    /** Where install.sh puts the launcher. */
    static final String CLAUDE_LAUNCH = TERMUX_HOME + "/bin/claude-launch";

    /** The single command that performs the entire Termux-side setup. */
    static final String SETUP_COMMAND =
            "pkg install -y curl && curl -fsSL "
                    + "https://github.com/ptrkhh/termux-claude-autoremote"
                    + "/releases/latest/download/install.sh | bash";

    // ---- result codes from run() ----
    /** Command ran and exited 0. */
    static final int OK = 0;
    /** Command ran but exited non-zero (for the probe: launcher not installed). */
    static final int NONZERO = 1;
    /** Termux refused the request — almost always allow-external-apps. */
    static final int REFUSED = 2;
    /** Termux never answered. Treated as "unknown", not as failure. */
    static final int TIMEOUT = 3;
    /** Termux isn't installed at all. */
    static final int NO_TERMUX = 4;
    /** The runtime RUN_COMMAND permission hasn't been granted. */
    static final int NO_PERMISSION = 5;

    interface ResultListener {
        /**
         * @param code   one of OK / NONZERO / REFUSED / TIMEOUT / NO_TERMUX / NO_PERMISSION
         * @param stdout what the command wrote, "" when there was nothing or no bundle
         * @param detail Termux's errmsg, or null
         */
        void onResult(int code, String stdout, String detail);
    }

    /**
     * Whether a package is installed. Android 11+ hides packages from this
     * unless they are listed in the manifest's {@code <queries>} block — the
     * three Termux apps are.
     */
    static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean termuxInstalled(Context ctx) {
        return installed(ctx, TERMUX_PACKAGE);
    }

    static boolean permissionGranted(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || ctx.checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    static void copySetupCommand(Context ctx) {
        ClipboardManager cb = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("claude setup", SETUP_COMMAND));
        }
    }

    /** Brings Termux to the front. Returns false if it isn't installed. */
    static boolean openTermux(Context ctx) {
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch == null) {
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(launch);
        return true;
    }

    /** Termux reports a foreground command only when it exits, so a timeout here means success. */
    static final long ERROR_WINDOW_MS = 3000;
    /** The read verbs return in milliseconds; this is today's probe() budget. */
    static final long TIMEOUT_READ = 6000;
    /** --sessions spawns ~100 short-lived processes on an ARM device. */
    static final long TIMEOUT_SESSIONS = 12000;

    /**
     * Reads the script before running it, so an old script can never raise a
     * termux-dialog from a background command. Both paths are absolute:
     * RUN_COMMAND_PATH goes straight to execve, and a tilde arriving as bash's
     * $0 is never expanded (bash does tilde expansion before parameter
     * expansion, on literal text only) — a tilde path makes grep exit 2, which
     * this app reports as "script absent" on a perfectly healthy install.
     *
     * <p>Exit codes: 0 with state on stdout, 1 stale, 2 absent, 126 not
     * executable. The grep short-circuits before exec, which is what makes this
     * safe against arbitrarily old scripts. It proves the file exists, is
     * runnable and mentions --state — not that it implements it; the caller's
     * required-tag check closes that gap.
     */
    static void state(Context ctx, ResultListener cb) {
        run(ctx, TERMUX_PREFIX + "/bin/bash",
                new String[]{"-c", "grep -q -- --state \"$0\" && exec \"$0\" --state", CLAUDE_LAUNCH},
                TERMUX_HOME, true, TIMEOUT_READ, cb);
    }

    static void ls(Context ctx, String dir, ResultListener cb) {
        run(ctx, CLAUDE_LAUNCH, new String[]{"--ls", dir}, TERMUX_HOME, true, TIMEOUT_READ, cb);
    }

    static void sessions(Context ctx, ResultListener cb) {
        run(ctx, CLAUDE_LAUNCH, new String[]{"--sessions"}, TERMUX_HOME, true, TIMEOUT_SESSIONS, cb);
    }

    static void launch(Context ctx, String dir, String model, String effort, ResultListener cb) {
        run(ctx, CLAUDE_LAUNCH,
                new String[]{"--launch", "--dir", dir, "--model", model, "--effort", effort},
                dir, false, ERROR_WINDOW_MS, cb);
    }

    static void resume(Context ctx, String dir, String sid, ResultListener cb) {
        run(ctx, CLAUDE_LAUNCH,
                new String[]{"--launch", "--dir", dir, "--resume", sid},
                dir, false, ERROR_WINDOW_MS, cb);
    }

    /**
     * Fires a Termux RUN_COMMAND and reports back once — on Termux's result, on
     * an exception, or on timeout, whichever lands first.
     */
    private static void run(Context ctx, String path, String[] args, String workdir,
                            boolean background, long timeoutMs, final ResultListener cb) {
        if (!termuxInstalled(ctx)) {
            cb.onResult(NO_TERMUX, "", null);
            return;
        }
        if (!permissionGranted(ctx)) {
            cb.onResult(NO_PERMISSION, "", null);
            return;
        }

        final Context app = ctx.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] answered = {false};
        // A per-call UUID, not a counter. `callSeq` was static, so it reset to 0
        // on process death: after a --launch the app can be killed while claude
        // runs for hours holding Termux's PendingIntent, and the next --state
        // would rebuild a filterEquals-equal Intent with the same request code.
        // FLAG_UPDATE_CURRENT then hands back the *stale* token, so when the old
        // session finally exits its terminal transcript is delivered to the
        // --state receiver — a bogus "re-run setup" on a healthy install, and it
        // steals the real reply's answered[0] slot. A unique action makes the
        // collision structurally impossible.
        // FLAG_ONE_SHOT and pi.cancel() were considered and rejected: cancel()
        // also runs on the 3s timeout path, destroying a token Termux still holds
        // for a live session, and FLAG_ONE_SHOT contradicts FLAG_UPDATE_CURRENT.
        final String action = app.getPackageName() + ".RUN_RESULT." + UUID.randomUUID();

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                deliver(app, this, handler, answered, parse(intent), stdout(intent), detail(intent), cb);
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }

        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", path);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args);
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir);
        // false -> Termux opens a foreground terminal session, which the
        // interactive Claude Code process needs. true -> silent, for the probe.
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        // Termux fills this in with a "result" bundle. Must be mutable for that.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        Intent resultIntent = new Intent(action).setPackage(app.getPackageName());
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",
                PendingIntent.getBroadcast(app, action.hashCode(), resultIntent, flags));

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception e) {
            deliver(app, receiver, handler, answered, REFUSED, "", e.getMessage(), cb);
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                deliver(app, receiver, handler, answered, TIMEOUT, "", null, cb);
            }
        }, timeoutMs);
    }

    /** First caller wins; unregisters the receiver and reports exactly once. */
    private static void deliver(Context app, BroadcastReceiver receiver, Handler handler,
                                boolean[] answered, int code, String stdout, String detail,
                                ResultListener cb) {
        if (answered[0]) {
            return;
        }
        answered[0] = true;
        handler.removeCallbacksAndMessages(null);
        try {
            app.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // already gone
        }
        cb.onResult(code, stdout, detail);
    }

    /**
     * Termux packs its outcome into a "result" bundle on the callback intent.
     *
     * <p>A null bundle is a <em>failure</em>, not success. It used to return OK,
     * which was harmless only while stdout was discarded: once a caller reads
     * stdout, "Termux answered with nothing" would render as a healthy-looking
     * empty folder list with the loading row resolved and no message.
     */
    private static int parse(Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        if (result == null) {
            return NONZERO;
        }
        String err = result.getString("errmsg");
        if (err != null && !err.isEmpty()) {
            return REFUSED;
        }
        return result.getInt("exitCode", 0) == 0 ? OK : NONZERO;
    }

    private static String stdout(Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        if (result == null) {
            return "";
        }
        String out = result.getString("stdout");
        return out == null ? "" : out;
    }

    private static String detail(Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        return result == null ? null : result.getString("errmsg");
    }
}
