package com.megarohas.tvvoice;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/** One line of feedback drawn over whatever is on screen (Flip Clock), then gone. */
final class Overlay {

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hideNow;
    private TextView view;

    Overlay(Context ctx) {
        this.ctx = ctx;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void show(final String text, final long ms) {
        ui.post(() -> {
            if (!Settings.canDrawOverlays(ctx)) {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
                return;
            }
            if (view == null) {
                view = new TextView(ctx);
                view.setTextColor(Color.WHITE);
                view.setTextSize(34);
                view.setPadding(dp(36), dp(16), dp(36), dp(16));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xCC000000);
                bg.setCornerRadius(dp(28));
                view.setBackground(bg);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.y = dp(48);
                wm.addView(view, lp);
            }
            view.setText(text);
            ui.removeCallbacks(hide);
            ui.postDelayed(hide, ms);
        });
    }

    void hide() {
        ui.post(hide);
    }

    private void hideNow() {
        if (view != null) {
            wm.removeView(view);
            view = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
