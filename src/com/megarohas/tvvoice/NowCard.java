package com.megarohas.tvvoice;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** «What's playing» strip over the clock: title, progress, time left, the sleep timer. */
final class NowCard {

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::render; // keeps the progress moving between polls
    private LinearLayout view;
    private TextView title, meta;
    private ProgressBar bar;
    private BoxHelper.Now now;
    private String note = "";

    NowCard(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void update(final BoxHelper.Now n, final String timer) {
        ui.post(() -> {
            now = n;
            note = timer;
            render();
        });
    }

    void hide() {
        ui.post(() -> {
            now = null;
            render();
        });
    }

    private void render() {
        ui.removeCallbacks(tick);
        BoxHelper.Now n = now;
        if (n == null || !n.active() || !Store.card(ctx) || !Settings.canDrawOverlays(ctx)) {
            if (view != null) {
                wm.removeView(view);
                view = null;
            }
            return;
        }
        if (view == null) create();
        title.setText(("paused".equals(n.state) ? "⏸  " : "▶  ") + (n.title.isEmpty() ? Commands.app(n.pkg) : n.title));
        long pos = n.position();
        StringBuilder m = new StringBuilder(Commands.app(n.pkg));
        if (n.duration > 0) {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress((int) Math.min(1000, 1000 * pos / n.duration));
            m.append("  ·  осталось ").append(Commands.human(n.duration - pos));
        } else {
            bar.setVisibility(View.GONE);
        }
        if (!note.isEmpty()) m.append("  ·  ").append(note);
        meta.setText(m);
        ui.postDelayed(tick, 1000);
    }

    private void create() {
        int maxWidth = Math.round(ctx.getResources().getDisplayMetrics().widthPixels * 0.7f);
        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(22), dp(12), dp(22), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(dp(20));
        view.setBackground(bg);
        title = new TextView(ctx);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(maxWidth);
        view.addView(title);
        bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        view.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));
        meta = new TextView(ctx);
        meta.setTextColor(0xCCFFFFFF);
        meta.setTextSize(15);
        meta.setSingleLine(true);
        meta.setMaxWidth(maxWidth);
        view.addView(meta);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(16);
        wm.addView(view, lp);
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
