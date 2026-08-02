package com.ptrkhh.openclaudehere;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * View helpers shared by the two screens. The app ships no layout XML, so these
 * are the layout language: everything is built in code.
 *
 * <p>Extracted from MainActivity because LaunchActivity needs the same helpers
 * and duplicating them across two activities is the kind of drift that ends with
 * two different paddings.
 */
final class Views {

    private Views() {}

    static final int GREEN = Color.parseColor("#2E7D32");
    static final int RED = Color.parseColor("#C62828");
    static final int GREY = Color.parseColor("#666666");
    static final int INK = Color.parseColor("#222222");

    /** Minimum touch target. Every interactive row gets at least this. */
    static final int TOUCH_TARGET_DP = 48;

    static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    static TextView text(Context ctx, String s, int sp, int color, int style) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, style);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    static TextView heading(Context ctx, String s) {
        TextView tv = text(ctx, s, 17, INK, Typeface.BOLD);
        ((LinearLayout.LayoutParams) tv.getLayoutParams()).topMargin = dp(ctx, 28);
        return tv;
    }

    static Button button(Context ctx, LinearLayout parent, String label, View.OnClickListener onClick) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        b.setMinHeight(dp(ctx, TOUCH_TARGET_DP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        parent.addView(b, lp);
        return b;
    }
}
