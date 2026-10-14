package com.megarohas.tvvoice;

import android.app.Activity;

/**
 * Invisible trampoline: a microphone service may only start (or get its
 * microphone back) while the app is on screen, so boot, updates and the
 * service itself come through here for a split second.
 */
public class StartActivity extends Activity {

    @Override
    protected void onResume() {
        super.onResume();
        ListenService.start(this, getIntent().getBooleanExtra(ListenService.ACTION_REOPEN, false));
        finish();
        overridePendingTransition(0, 0);
    }
}
