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
        void onResult(int code, String detail);
    }

    /** Distinct request codes / actions so two in-flight calls can't collide. */
    private static int callSeq = 0;

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

    /**
     * Asks Termux to run {@code ~/bin/claude-launch --dir <dir>} in a foreground
     * terminal session. Errors (bad path, allow-external-apps off) come back
     * within a second or two; success does not come back until Claude Code
     * itself exits, so callers should treat a timeout as "looks fine".
     */
    static void launchClaude(Context ctx, String dir, long timeoutMs, ResultListener cb) {
        run(ctx, CLAUDE_LAUNCH, new String[]{"--dir", dir}, dir, false, timeoutMs, cb);
    }

    /**
     * Runs {@code test -x ~/bin/claude-launch} in the background purely to find
     * out how far setup got: {@link #OK} means everything is wired up,
     * {@link #NONZERO} means Termux is reachable but install.sh hasn't run,
     * {@link #REFUSED}/{@link #TIMEOUT} means Termux won't take commands yet.
     */
    static void probe(Context ctx, ResultListener cb) {
        run(ctx, TERMUX_PREFIX + "/bin/test", new String[]{"-x", CLAUDE_LAUNCH},
                TERMUX_HOME, true, 6000, cb);
    }

    /**
     * Fires a Termux RUN_COMMAND and reports back once — on Termux's result, on
     * an exception, or on timeout, whichever lands first.
     */
    private static void run(Context ctx, String path, String[] args, String workdir,
                            boolean background, long timeoutMs, final ResultListener cb) {
        if (!termuxInstalled(ctx)) {
            cb.onResult(NO_TERMUX, null);
            return;
        }
        if (!permissionGranted(ctx)) {
            cb.onResult(NO_PERMISSION, null);
            return;
        }

        final Context app = ctx.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] answered = {false};
        final String action = app.getPackageName() + ".RUN_RESULT." + (callSeq++);

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                deliver(app, this, handler, answered, parse(intent), detail(intent), cb);
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
                PendingIntent.getBroadcast(app, callSeq, resultIntent, flags));

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception e) {
            deliver(app, receiver, handler, answered, REFUSED, e.getMessage(), cb);
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                deliver(app, receiver, handler, answered, TIMEOUT, null, cb);
            }
        }, timeoutMs);
    }

    /** First caller wins; unregisters the receiver and reports exactly once. */
    private static void deliver(Context app, BroadcastReceiver receiver, Handler handler,
                                boolean[] answered, int code, String detail, ResultListener cb) {
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
        cb.onResult(code, detail);
    }

    /** Termux packs its outcome into a "result" bundle on the callback intent. */
    private static int parse(Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        if (result == null) {
            return OK; // answered without detail — nothing to complain about
        }
        String err = result.getString("errmsg");
        if (err != null && !err.isEmpty()) {
            return REFUSED;
        }
        return result.getInt("exitCode", 0) == 0 ? OK : NONZERO;
    }

    private static String detail(Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        return result == null ? null : result.getString("errmsg");
    }
}
