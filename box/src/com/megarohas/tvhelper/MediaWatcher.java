package com.megarohas.tvhelper;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * Runs on the TV box. Holding notification access is what lets an app see the
 * media sessions of other apps (YouTube, Кинопоиск, Plex…), so this listener
 * keeps their controllers at hand and serves them to the tablet over the LAN.
 */
public class MediaWatcher extends NotificationListenerService {

    static final String TAG = "tvhelper";

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = list ->
            sessions = list != null ? list : Collections.<MediaController>emptyList();
    private volatile List<MediaController> sessions = Collections.emptyList();
    private MediaSessionManager msm;
    private Server server;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        sessions = msm.getActiveSessions(me);
        msm.addOnActiveSessionsChangedListener(changed, me);
        server = new Server(this);
        server.start();
        Log.i(TAG, "listening on :" + Server.PORT);
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(changed);
        if (server != null) server.shutdown();
        server = null;
    }

    /** The session worth talking about: one that plays, else the latest one that's alive. */
    MediaController current() {
        List<MediaController> list = sessions;
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() != PlaybackState.STATE_NONE && s.getState() != PlaybackState.STATE_ERROR
                    && s.getState() != PlaybackState.STATE_STOPPED) return c;
        }
        return null;
    }
}
