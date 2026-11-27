package com.castel.generatedapp;

import android.content.ContentUris;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Plays device songs with Android's own MediaPlayer instead of the WebView.
 *
 * Why: audio played inside the WebView (especially through Web Audio for boost/EQ) stutters
 * when the page is busy, is throttled in the background and can stall mid-song. Here the
 * song plays in the app process, holds a wake lock, handles audio focus, and the next song is
 * prepared in advance (setNextMediaPlayer) so songs change over without a gap even if the
 * page is slow. Boost, bass and EQ use Android's audio effects on one shared audio session.
 *
 * All methods must run on the main thread (MediaBridge posts to it).
 * Events go to the page as window.CastelMediaEngine(type, data).
 */
final class NativeAudio {
    private static final String TAG = "CastelAppFactory";
    interface Sink { void emit(String type, JSONObject data); }

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private final int session;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    private MediaPlayer cur, next;
    private long curId = -1, nextId = -1;
    private int token;                 // page's load counter, echoed in events
    private boolean prepared, playing, wantPlay, nextReady;
    private long pendingSeek = -1;
    private float left = 1f, right = 1f, duck = 1f, speed = 1f;

    private Equalizer eq;
    private BassBoost bass;
    private LoudnessEnhancer loud;
    private DynamicsProcessing dyn;   // Android 9+: boost with a fast peak limiter (no slow pumping)
    private boolean dynFailed;
    private JSONObject lastFx;

    private AudioFocusRequest focusReq;
    private boolean hasFocus, resumeOnGain;

    NativeAudio(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
        am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        int s = 0;
        try { s = am.generateAudioSessionId(); } catch (Exception ignored) { }
        session = s > 0 ? s : 0;
    }

    boolean isPlaying() { return playing; }
    boolean isActive() { return cur != null; }

    // ------------------------------------------------------------------ Commands from the page

    void load(long id, long startMs, boolean play, int tok) {
        releasePlayers();
        token = tok;
        curId = id;
        wantPlay = play;
        pendingSeek = startMs > 0 ? startMs : -1;
        cur = create(id, false);
        if (cur == null) { error(-1004, "open failed"); }
    }

    void play() {
        wantPlay = true;
        if (cur == null || !prepared) return;
        if (!requestFocus()) { emitState(); return; }
        try {
            cur.start();
            applySpeed(cur);
            playing = true;
        } catch (Exception e) { Log.w(TAG, "MEDIA_ENGINE_START", e); }
        emitState();
        tick();
    }

    void pause() {
        wantPlay = false;
        resumeOnGain = false;
        if (cur != null && prepared) { try { if (cur.isPlaying()) cur.pause(); } catch (Exception ignored) { } }
        playing = false;
        emitState();
    }

    void seek(long ms) {
        if (cur == null) return;
        if (!prepared) { pendingSeek = ms; return; }
        try {
            if (Build.VERSION.SDK_INT >= 26) cur.seekTo(Math.max(0, ms), MediaPlayer.SEEK_PREVIOUS_SYNC);
            else cur.seekTo((int) Math.max(0, ms));
        } catch (Exception ignored) { }
        emitTime();
    }

    /** The song to play after this one (-1: none). Prepared now so the change-over is gapless. */
    void setNext(long id) {
        if (id == nextId && next != null) return;
        if (next != null) { try { if (cur != null) cur.setNextMediaPlayer(null); } catch (Exception ignored) { } next.release(); next = null; }
        nextId = id;
        nextReady = false;
        if (id < 0 || cur == null) return;
        next = create(id, true);
    }

    void volume(float l, float r) { left = clamp(l); right = clamp(r); applyVolume(); }

    void speed(float s) {
        speed = Math.max(0.25f, Math.min(4f, s));
        if (cur != null && playing) applySpeed(cur);
    }

    /** {eqOn, eq:[10 dB values at 32..16k Hz], bass: 0-12, boost: 100-300, limiter} → what the device supports. */
    String effects(String json) {
        JSONObject caps = new JSONObject();
        try {
            JSONObject fx = new JSONObject(json == null ? "{}" : json);
            lastFx = fx;
            ensureEffects();
            boolean eqOn = fx.optBoolean("eqOn", false);
            JSONArray g = fx.optJSONArray("eq");
            if (eq != null) {
                try {
                    short bands = eq.getNumberOfBands();
                    short[] range = eq.getBandLevelRange();
                    for (short b = 0; b < bands; b++) {
                        double hz = eq.getCenterFreq(b) / 1000.0;
                        double db = eqOn && g != null ? interp(g, hz) : 0;
                        int mb = (int) Math.round(db * 100);
                        eq.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], mb)));
                    }
                    eq.setEnabled(eqOn);
                    caps.put("eq", true);
                } catch (Exception e) { caps.put("eq", false); }
            } else caps.put("eq", false);
            int bassDb = fx.optInt("bass", 0);
            if (bass != null) {
                try {
                    if (bass.getStrengthSupported()) bass.setStrength((short) Math.min(1000, bassDb * 1000 / 12));
                    bass.setEnabled(bassDb > 0);
                    caps.put("bass", true);
                } catch (Exception e) { caps.put("bass", false); }
            } else caps.put("bass", false);
            int boost = fx.optInt("boost", 100);
            boolean guard = boost > 100 || bassDb > 0 || (eqOn && maxGain(g) > 0);
            if (applyDynamics(boost, guard)) {
                // Boost and clipping protection are handled by DynamicsProcessing; the older
                // LoudnessEnhancer would squeeze loud parts and let them recover slowly
                // (heard as the music going weak for a few seconds), so keep it off.
                if (loud != null) { try { loud.setEnabled(false); } catch (Exception ignored) { } }
                caps.put("boost", true);
            } else if (loud != null) {
                try {
                    int mb = boost > 100 ? (int) Math.round(2000 * Math.log10(boost / 100.0)) : 0;
                    loud.setTargetGain(mb);
                    loud.setEnabled(boost > 100);
                    caps.put("boost", true);
                } catch (Exception e) { caps.put("boost", false); }
            } else caps.put("boost", false);
        } catch (Exception e) {
            Log.w(TAG, "MEDIA_ENGINE_FX", e);
        }
        return caps.toString();
    }

    void stop() {
        wantPlay = false;
        releasePlayers();
        abandonFocus();
        emitState();
    }

    void release() {
        stop();
        releaseEffects();
    }

    /** Synchronous snapshot for the page. */
    String state() {
        JSONObject o = new JSONObject();
        try {
            o.put("token", token);
            o.put("id", curId);
            o.put("playing", playing);
            o.put("pos", position());
            o.put("dur", duration());
            o.put("ready", prepared);
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ------------------------------------------------------------------ Players

    private MediaPlayer create(long id, boolean asNext) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            if (session > 0) mp.setAudioSessionId(session);
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            mp.setDataSource(ctx, uri);
        } catch (Exception e) {
            Log.w(TAG, "MEDIA_ENGINE_OPEN " + id, e);
            mp.release();
            return null;
        }
        final int myToken = token;
        mp.setOnPreparedListener(p -> {
            if (asNext) {
                if (p != next || cur == null) return;
                p.setVolume(left * duck, right * duck);
                try { cur.setNextMediaPlayer(p); nextReady = true; } catch (Exception e) { Log.w(TAG, "MEDIA_ENGINE_NEXT", e); }
                return;
            }
            if (p != cur || myToken != token) return;
            prepared = true;
            ensureEffects();
            if (lastFx != null) effects(lastFx.toString());
            applyVolume();
            if (pendingSeek > 0) { seek(pendingSeek); pendingSeek = -1; }
            emit("loaded", obj("dur", duration()));
            if (next != null && nextReady) { try { cur.setNextMediaPlayer(next); } catch (Exception ignored) { } }
            if (wantPlay) play(); else emitState();
        });
        mp.setOnCompletionListener(p -> {
            if (p != cur) return;
            if (next != null && nextReady) {
                // Android has already started the next song.
                MediaPlayer old = cur;
                cur = next; curId = nextId; next = null; nextId = -1; nextReady = false;
                prepared = true; playing = true;
                old.release();
                applySpeed(cur);
                emit("advanced", obj("id", curId, "dur", duration()));
                tick();
            } else {
                playing = false;
                wantPlay = false;
                emit("ended", obj("id", curId));
            }
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p == next) { p.release(); next = null; nextReady = false; nextId = -1; return true; }
            if (p != cur) return true;
            Log.w(TAG, "MEDIA_ENGINE_ERROR what=" + what + " extra=" + extra);
            error(what, "extra " + extra);
            return true;
        });
        mp.setOnInfoListener((p, what, extra) -> {
            if (p != cur) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) emit("waiting", obj());
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) emitState();
            return false;
        });
        mp.setOnSeekCompleteListener(p -> { if (p == cur) emit("seeked", obj("pos", position())); });
        try { mp.prepareAsync(); } catch (Exception e) { mp.release(); return null; }
        return mp;
    }

    private void releasePlayers() {
        main.removeCallbacks(ticker);
        if (next != null) { try { next.release(); } catch (Exception ignored) { } next = null; }
        if (cur != null) { try { cur.release(); } catch (Exception ignored) { } cur = null; }
        nextId = -1; curId = -1; nextReady = false; prepared = false; playing = false;
    }

    private void error(int code, String msg) {
        playing = false;
        if (cur != null) { try { cur.release(); } catch (Exception ignored) { } cur = null; }
        if (next != null) { try { next.release(); } catch (Exception ignored) { } next = null; }
        prepared = false; nextReady = false; nextId = -1;
        emit("error", obj("code", code, "message", msg));
    }

    private long position() {
        if (cur == null || !prepared) return pendingSeek > 0 ? pendingSeek : 0;
        try { return cur.getCurrentPosition(); } catch (Exception e) { return 0; }
    }

    private long duration() {
        if (cur == null || !prepared) return 0;
        try { return Math.max(0, cur.getDuration()); } catch (Exception e) { return 0; }
    }

    private void applyVolume() {
        float d = duck;
        try { if (cur != null) cur.setVolume(left * d, right * d); } catch (Exception ignored) { }
        try { if (next != null) next.setVolume(left * d, right * d); } catch (Exception ignored) { }
    }

    private void applySpeed(MediaPlayer mp) {
        if (Build.VERSION.SDK_INT < 23 || mp == null) return;
        try {
            PlaybackParams pp = mp.getPlaybackParams();
            if (Math.abs(pp.getSpeed() - speed) < 0.001f) return;
            mp.setPlaybackParams(pp.setSpeed(speed).setPitch(1f));
        } catch (Exception e) { Log.w(TAG, "MEDIA_ENGINE_SPEED", e); }
    }

    // ------------------------------------------------------------------ Effects (one shared session)

    private void ensureEffects() {
        if (session <= 0) return;
        if (eq == null) { try { eq = new Equalizer(0, session); } catch (Throwable t) { eq = null; } }
        if (bass == null) { try { bass = new BassBoost(0, session); } catch (Throwable t) { bass = null; } }
        if (loud == null && Build.VERSION.SDK_INT >= 19) { try { loud = new LoudnessEnhancer(session); } catch (Throwable t) { loud = null; } }
    }

    private static double maxGain(JSONArray g) {
        double m = 0;
        if (g != null) for (int i = 0; i < g.length(); i++) m = Math.max(m, g.optDouble(i, 0));
        return m;
    }

    /** Volume boost as plain gain followed by a fast limiter. Returns false where unavailable. */
    private boolean applyDynamics(int boost, boolean on) {
        if (Build.VERSION.SDK_INT < 28 || session <= 0 || dynFailed) return false;
        // Not needed (no boost, no bass, no raised EQ band): don't build it. Setting it up talks
        // to Android's audio server and can hold up the app for a moment.
        if (!on && dyn == null) return true;
        try {
            if (dyn == null) {
                // Every stage needs at least one band, even switched off: Android rejects 0.
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        false, 1, false, 1, false, 1, true).build();
                dyn = new DynamicsProcessing(0, session, cfg);
            }
            float gainDb = boost > 100 ? (float) (20 * Math.log10(boost / 100.0)) : 0f;
            dyn.setInputGainAllChannelsTo(gainDb);
            // Attack 1 ms, release 80 ms, ratio 10:1 just under full scale: peaks are caught
            // at once and the level comes straight back, so there is no audible dip.
            dyn.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -1f, 0f));
            dyn.setEnabled(on);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "MEDIA_ENGINE_DYNAMICS", t);
            try { if (dyn != null) dyn.release(); } catch (Throwable ignored) { }
            dyn = null;
            dynFailed = true;
            return false;
        }
    }

    private void releaseEffects() {
        try { if (eq != null) eq.release(); } catch (Exception ignored) { }
        try { if (bass != null) bass.release(); } catch (Exception ignored) { }
        try { if (loud != null) loud.release(); } catch (Exception ignored) { }
        try { if (dyn != null) dyn.release(); } catch (Throwable ignored) { }
        eq = null; bass = null; loud = null; dyn = null;
    }

    private static final double[] BANDS = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    /** dB at `hz`, interpolated in log-frequency between the page's 10 bands. */
    private static double interp(JSONArray g, double hz) {
        if (hz <= BANDS[0]) return g.optDouble(0, 0);
        for (int i = 1; i < BANDS.length; i++) {
            if (hz <= BANDS[i]) {
                double t = (Math.log(hz) - Math.log(BANDS[i - 1])) / (Math.log(BANDS[i]) - Math.log(BANDS[i - 1]));
                return g.optDouble(i - 1, 0) * (1 - t) + g.optDouble(i, 0) * t;
            }
        }
        return g.optDouble(BANDS.length - 1, 0);
    }

    // ------------------------------------------------------------------ Audio focus

    private final AudioManager.OnAudioFocusChangeListener focus = change -> main.post(() -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                hasFocus = false; resumeOnGain = false;
                if (playing) { pauseInternal(); emit("interrupted", obj("resume", false)); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) { resumeOnGain = true; pauseInternal(); emit("interrupted", obj("resume", true)); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Android 8+ already lowers the music by itself for a notification sound
                // (setWillPauseWhenDucked(false)). Lowering it here as well made the music
                // drop far below the volume the user chose, so only older Android ducks here.
                if (Build.VERSION.SDK_INT < 26) { duck = 0.5f; applyVolume(); }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (duck != 1f) { duck = 1f; applyVolume(); }
                if (resumeOnGain) { resumeOnGain = false; play(); }
                break;
            default: break;
        }
    });

    private void pauseInternal() {
        if (cur != null && prepared) { try { if (cur.isPlaying()) cur.pause(); } catch (Exception ignored) { } }
        playing = false;
        emitState();
    }

    private boolean requestFocus() {
        if (hasFocus) return true;
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs).setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focus, main).build();
            r = am.requestAudioFocus(focusReq);
        } else {
            r = am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }

    private void abandonFocus() {
        if (!hasFocus) return;
        if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
        else am.abandonAudioFocus(focus);
        hasFocus = false;
    }

    // ------------------------------------------------------------------ Events

    private final Runnable ticker = this::tick;

    private void tick() {
        main.removeCallbacks(ticker);
        if (cur == null || !playing) return;
        emitTime();
        main.postDelayed(ticker, 1000);
    }

    private void emitTime() { emit("time", obj("pos", position(), "dur", duration())); }

    private void emitState() {
        emit("state", obj("playing", playing, "pos", position(), "dur", duration(), "ready", prepared));
    }

    private void emit(String type, JSONObject data) {
        try { data.put("token", token); data.put("id", curId); } catch (Exception ignored) { }
        sink.emit(type, data);
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); } catch (Exception ignored) { }
        return o;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
