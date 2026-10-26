package com.castel.generatedapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps music playing in the background and shows the media notification
 * (notification shade, lock screen, Bluetooth/headset buttons, Android Auto-style controls).
 * Driven by MediaBridge.update(); controls are sent back to the page through MediaBridge.dispatch().
 */
public class MediaPlaybackService extends Service {
    private static final String TAG = "CastelAppFactory";
    private static final String CHANNEL = "castel_media";
    private static final int NOTIFICATION_ID = 7341;
    static final String ACTION_PLAY = "castel.media.PLAY";
    static final String ACTION_PAUSE = "castel.media.PAUSE";
    static final String ACTION_NEXT = "castel.media.NEXT";
    static final String ACTION_PREV = "castel.media.PREV";
    static final String ACTION_DISMISS = "castel.media.DISMISS";

    static final class State {
        String state = "none", title = "", artist = "", album = "", art = "";
        long duration, position, mediaId = -1;
        float rate = 1f;
        boolean hasPrev = true, hasNext = true;
        boolean playing() { return "playing".equals(state); }
    }

    static volatile MediaBridge bridge;
    private static volatile State latest;
    private static MediaPlaybackService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private MediaSessionCompat session;
    private NotificationManager nm;
    private boolean noisyRegistered, foreground;
    private Bitmap artBitmap;
    private String artKey = "";

    // ---------------------------------------------------------------- Called from MediaBridge (main thread)

    static void push(Context ctx, State s) {
        latest = s;
        if (instance != null) { instance.apply(s); return; }
        if (!s.playing()) return; // Nothing to show until something plays.
        Intent i = new Intent(ctx, MediaPlaybackService.class);
        try {
            ContextCompat.startForegroundService(ctx, i);
        } catch (Exception e) {
            Log.w(TAG, "MEDIA_SERVICE_START_FAILED", e);
        }
    }

    static void shutdown(Context ctx) {
        latest = null;
        if (instance != null) instance.stopEverything();
    }

    // ---------------------------------------------------------------- Lifecycle

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Music controls");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSessionCompat(this, "CastelMedia");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { send("play", 0); }
            @Override public void onPause() { send("pause", 0); }
            @Override public void onSkipToNext() { send("next", 0); }
            @Override public void onSkipToPrevious() { send("prev", 0); }
            @Override public void onSeekTo(long pos) { send("seek", pos); }
            @Override public void onStop() { send("pause", 0); }
            @Override public void onFastForward() { State s = latest; send("seek", (s == null ? 0 : currentPosition(s)) + 10000); }
            @Override public void onRewind() { State s = latest; send("seek", Math.max(0, (s == null ? 0 : currentPosition(s)) - 10000)); }
        });
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            session.setSessionActivity(PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        State s = latest;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISMISS.equals(action)) { stopEverything(); MediaBridge b = bridge; if (b != null) b.sessionEnded(); return START_NOT_STICKY; }
        if (s == null || bridge == null) {
            // Started without anything to show (for example after the app was closed): satisfy
            // the foreground-service contract, then stop.
            enterForeground(build(new State()));
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            // The tap on the notification lets us become a foreground service again.
            enterForeground(build(s));
            send("play", 0);
        } else if (ACTION_PAUSE.equals(action)) send("pause", 0);
        else if (ACTION_NEXT.equals(action)) send("next", 0);
        else if (ACTION_PREV.equals(action)) send("prev", 0);
        else {
            // Started by push(): Android requires startForeground() even if playback paused meanwhile.
            if (!foreground) enterForeground(build(s));
            apply(s);
        }
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // The app was swiped away: the page is gone, so the controls would do nothing.
        stopEverything();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        unregisterNoisy();
        if (session != null) { session.setActive(false); session.release(); session = null; }
        artLoader.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- State → session + notification

    private void apply(State s) {
        if (session == null) return;
        if ("none".equals(s.state)) { stopEverything(); return; }
        loadArtAsync(s);
        MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, s.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.duration > 0 ? s.duration : -1);
        if (artBitmap != null) md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap);
        session.setMetadata(md.build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_FAST_FORWARD | PlaybackStateCompat.ACTION_REWIND;
        if (s.hasNext) actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if (s.hasPrev) actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(s.playing() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        s.position, s.playing() ? s.rate : 0f, SystemClock.elapsedRealtime())
                .build());
        session.setActive(true);
        Notification n = build(s);
        if (s.playing()) {
            // Audio focus is left to the WebView: it already requests it for the playing
            // song and pauses for calls. Requesting it here too makes the two fight and
            // Android pauses the song.
            registerNoisy();
            enterForeground(n);
        } else {
            unregisterNoisy();
            if (foreground) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH); else stopForeground(false);
                foreground = false;
            }
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        }
    }

    private void enterForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, n);
            foreground = true;
        } catch (Exception e) {
            // Android 12+ refuses when the app is in the background without an exemption.
            // Keep the controls visible; playback continues while Android allows it.
            Log.w(TAG, "MEDIA_FOREGROUND_REFUSED", e);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        }
    }

    private Notification build(State s) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.castel_media_note)
                .setContentTitle(s.title.isEmpty() ? "Music" : s.title)
                .setContentText(s.artist)
                .setSubText(s.album.isEmpty() ? null : s.album)
                .setLargeIcon(artBitmap)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(s.playing())
                .setDeleteIntent(service(ACTION_DISMISS, 5));
        if (session != null) b.setContentIntent(session.getController().getSessionActivity());
        b.addAction(R.drawable.castel_media_prev, "Previous", service(ACTION_PREV, 2));
        if (s.playing()) b.addAction(R.drawable.castel_media_pause, "Pause", service(ACTION_PAUSE, 3));
        else b.addAction(R.drawable.castel_media_play, "Play", service(ACTION_PLAY, 4));
        b.addAction(R.drawable.castel_media_next, "Next", service(ACTION_NEXT, 6));
        androidx.media.app.NotificationCompat.MediaStyle style = new androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    private PendingIntent service(String action, int code) {
        Intent i = new Intent(this, MediaPlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 26 && ACTION_PLAY.equals(action)) return PendingIntent.getForegroundService(this, code, i, flags);
        return PendingIntent.getService(this, code, i, flags);
    }

    private void loadArtAsync(State s) {
        String key = s.mediaId >= 0 ? "id:" + s.mediaId : (s.art.isEmpty() ? "" : "data:" + s.art.hashCode() + ":" + s.art.length());
        if (key.equals(artKey)) return;
        artKey = key;
        artBitmap = null;
        if (key.isEmpty()) return;
        artLoader.execute(() -> {
            Bitmap bmp = null;
            try {
                if (s.mediaId >= 0) {
                    bmp = MediaBridge.loadArt(this, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.mediaId), 512);
                } else {
                    int comma = s.art.indexOf(',');
                    byte[] bytes = Base64.decode(comma >= 0 ? s.art.substring(comma + 1) : s.art, Base64.DEFAULT);
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            } catch (Exception ignored) { }
            Bitmap result = bmp;
            main.post(() -> {
                if (!key.equals(artKey)) return;
                artBitmap = result;
                State cur = latest;
                if (cur != null && result != null) apply(cur);
            });
        });
    }

    private void stopEverything() {
        unregisterNoisy();
        if (session != null) session.setActive(false);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        foreground = false;
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        artKey = "";
        artBitmap = null;
        stopSelf();
    }

    private static long currentPosition(State s) { return s.position; }

    private void send(String action, long value) {
        MediaBridge b = bridge;
        if (b != null) b.dispatch(action, value);
    }

    // ---------------------------------------------------------------- Headphones

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // Headphones unplugged / Bluetooth disconnected: pause instead of blasting the speaker.
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) send("pause", 0);
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        ContextCompat.registerReceiver(this, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        noisyRegistered = false;
    }
}
