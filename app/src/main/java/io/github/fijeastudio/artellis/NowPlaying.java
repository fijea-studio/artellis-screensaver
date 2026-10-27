package io.github.fijeastudio.artellis;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * "Song · Artist" of whatever is playing (Spotify, YouTube Music...), for one quiet line under the date.
 * Display only: the screensaver takes no keys. Needs MediaListener allowed as a notification listener;
 * without it the line simply never shows.
 */
final class NowPlaying {

    interface Listener {
        /** The line to show, or null when nothing plays. Main thread. */
        void onChanged(String line);
    }

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final List<MediaController> controllers = new ArrayList<>();
    private MediaSessionManager manager;
    private String last;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::watch;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
        @Override public void onSessionDestroyed() { update(); }
    };

    NowPlaying(Context context, Handler handler, Listener listener) {
        this.context = context;
        this.handler = handler;
        this.listener = listener;
    }

    void start() {
        manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return;
        ComponentName me = new ComponentName(context, MediaListener.class);
        try {
            manager.addOnActiveSessionsChangedListener(sessions, me, handler);
            watch(manager.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.i(Feed.TAG, "no media access (notification listener not allowed): song line off");
            manager = null;
        }
    }

    void stop() {
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessions);
            } catch (RuntimeException ignored) {
            }
        }
        manager = null;
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
        last = null;
    }

    private void watch(List<MediaController> active) {
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers.clear();
        if (active != null) {
            for (MediaController c : active) {
                c.registerCallback(callback, handler);
                controllers.add(c);
            }
        }
        update();
    }

    private void update() {
        String line = null;
        for (MediaController c : controllers) {
            PlaybackState state = c.getPlaybackState();
            if (state == null || state.getState() != PlaybackState.STATE_PLAYING) continue;
            MediaMetadata m = c.getMetadata();
            if (m == null) continue;
            String title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (title.isEmpty()) continue;
            line = artist.isEmpty() ? title : title + "  ·  " + artist;
            break;
        }
        if (line == null ? last == null : line.equals(last)) return;
        last = line;
        listener.onChanged(line);
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String key : keys) {
            CharSequence s = m.getText(key);
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
        }
        return "";
    }
}
