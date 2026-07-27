package com.ptrkhh.openclaudehere;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Thin wrapper around Termux's {@code RUN_COMMAND} service — the one channel this
 * app uses to run things inside Termux. Shared by {@link ShareActivity} (start
 * {@code claude-launch --dir}) and {@link MainActivity} (shell the installer).
 *
 * <p>Note the hard constraint this class exists around: Termux runs as its own
 * app UID and can only execute files it can read — i.e. files under its own
 * {@code /data/data/com.termux/files} tree. It cannot read this app's private
 * files/cache (different UID sandbox). So a command either points at a path that
 * already lives inside Termux, or — for the installer, which has nothing in
 * Termux yet — runs Termux's own {@code bash} with the script passed inline as a
 * {@code -c} argument.
 */
final class Termux {

    static final String PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    // Termux declares RUN_COMMAND as a *dangerous* (runtime) permission, so it is
    // never granted just by being listed in the manifest — it must be requested.
    static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    // Termux's own bash and home — both inside Termux's readable tree.
    static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    static final String HOME = "/data/data/com.termux/files/home";

    private Termux() {}

    /**
     * Ask Termux to run {@code path args...} in {@code workdir}. With
     * {@code background == false} Termux opens a foreground terminal session so
     * the user sees output (and any error).
     *
     * @throws SecurityException if Termux rejects the request — this is Termux's
     *     own {@code allow-external-apps} gate, which only takes effect after a
     *     full Termux restart.
     * @throws RuntimeException (or other) if Termux can't be reached at all.
     */
    static void runCommand(Context ctx, String path, String[] args, String workdir, boolean background) {
        Intent intent = new Intent();
        intent.setClassName(PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", path);
        if (args != null) {
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args);
        }
        if (workdir != null) {
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir);
        }
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }
}
