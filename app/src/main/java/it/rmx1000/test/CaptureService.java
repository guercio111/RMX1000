package it.rmx1000.test;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static final String ACTION_START = "it.rmx1000.test.START";
    public static final String ACTION_STOP = "it.rmx1000.test.STOP";
    public static final String ACTION_ECHO = "it.rmx1000.test.ECHO";
    public static final String ACTION_MODE = "it.rmx1000.test.MODE";
    public static final String ACTION_ISOLATE = "it.rmx1000.test.ISOLATE";
    public static final String ACTION_ISOLATE_MODE = "it.rmx1000.test.ISOLATE_MODE";
    public static final String ACTION_TEMPO = "it.rmx1000.test.TEMPO";
    public static final String ACTION_FX_PARAM = "it.rmx1000.test.FX_PARAM";
    public static final String ACTION_XPAD = "it.rmx1000.test.XPAD";
    public static final String ACTION_CLOCK_CONTROL = "it.rmx1000.test.CLOCK_CONTROL";
    public static final String EXTRA_BPM = "estimated_bpm";
    public static final String EXTRA_CLOCK_LOCKED = "clock_locked";
    public static final String EXTRA_CLOCK_DELTA_BPM = "clock_delta_bpm";
    public static final String EXTRA_CLOCK_TRIM_BPM = "clock_trim_bpm";
    public static final String EXTRA_CLOCK_RESET_AUTO = "clock_reset_auto";
    public static final String EXTRA_CLOCK_RESET_FADER = "clock_reset_fader";
    public static final String EXTRA_FX_PARAM = "fx_parameter";
    public static final String EXTRA_FX_LEVEL = "fx_level";
    public static final String EXTRA_FX_SUB1 = "fx_sub1";
    public static final String EXTRA_FX_SUB2 = "fx_sub2";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "projection_data";
    public static final String EXTRA_ENABLED = "echo_enabled";
    public static final String EXTRA_MODE = "fx_mode";
    public static final String EXTRA_BAND = "isolate_band";
    public static final String EXTRA_VALUE = "isolate_value";
    public static final String EXTRA_ISOLATE_MODE = "isolate_mode";
    public static final String EXTRA_LOW = "isolate_low";
    public static final String EXTRA_MID = "isolate_mid";
    public static final String EXTRA_HIGH = "isolate_high";
    public static final String EXTRA_XPAD_ROLL = "xpad_roll";
    public static final String EXTRA_XPAD_HOLD = "xpad_hold";
    public static final String EXTRA_XPAD_TOUCH = "xpad_touch";
    public static final String EXTRA_XPAD_DIVISION = "xpad_division";
    private static final String CHANNEL = "rmx_audio_capture";
    private static final int NOTIFICATION_ID = 1001;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final DspProcessor dsp = new DspProcessor();
    private volatile Thread audioThread;
    private MediaProjection projection;
    private AudioRecord recorder;
    private AudioTrack player;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopEngine(); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_ECHO.equals(action)) {
            dsp.setEnabled(intent.getBooleanExtra(EXTRA_ENABLED, false));
            return START_NOT_STICKY;
        }
        if (ACTION_MODE.equals(action)) {
            dsp.setMode(intent.getIntExtra(EXTRA_MODE, -1));
            return START_NOT_STICKY;
        }
        if (ACTION_ISOLATE_MODE.equals(action)) {
            dsp.setIsolateMode(intent.getIntExtra(EXTRA_ISOLATE_MODE, -1));
            return START_NOT_STICKY;
        }
        if (ACTION_FX_PARAM.equals(action)) {
            int parameter = intent.getIntExtra(EXTRA_FX_PARAM, 0);
            float value = intent.getFloatExtra(EXTRA_VALUE, 0.55f);
            if (parameter == 0) dsp.setFxLevel(value);
            else if (parameter == 1) dsp.setFxSub1(value);
            else if (parameter == 2) dsp.setFxSub2(value);
            return START_NOT_STICKY;
        }
        if (ACTION_CLOCK_CONTROL.equals(action)) {
            if (intent.getBooleanExtra(EXTRA_CLOCK_RESET_AUTO, false)) dsp.resetTempoToAuto();
            if (intent.getBooleanExtra(EXTRA_CLOCK_RESET_FADER, false)) dsp.resetTempoFader();
            if (intent.hasExtra(EXTRA_CLOCK_LOCKED)) dsp.setTempoLocked(intent.getBooleanExtra(EXTRA_CLOCK_LOCKED, false));
            if (intent.hasExtra(EXTRA_CLOCK_TRIM_BPM)) dsp.setTempoTrim(intent.getFloatExtra(EXTRA_CLOCK_TRIM_BPM, 0f));
            if (intent.hasExtra(EXTRA_CLOCK_DELTA_BPM)) dsp.adjustTempo(intent.getFloatExtra(EXTRA_CLOCK_DELTA_BPM, 0f));
            return START_NOT_STICKY;
        }
        if (ACTION_XPAD.equals(action)) {
            dsp.setXpadRoll(intent.getBooleanExtra(EXTRA_XPAD_ROLL, false));
            dsp.setXpadHold(intent.getBooleanExtra(EXTRA_XPAD_HOLD, false));
            dsp.setXpadTouch(intent.getBooleanExtra(EXTRA_XPAD_TOUCH, false),
                    intent.getIntExtra(EXTRA_XPAD_DIVISION, 2));
            return START_NOT_STICKY;
        }
        if (ACTION_ISOLATE.equals(action)) {
            dsp.setIsolateBand(intent.getIntExtra(EXTRA_BAND, -1), intent.getFloatExtra(EXTRA_VALUE, 0f));
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !running.get()) {
            startForeground(NOTIFICATION_ID, buildNotification());
            dsp.resetRhythm();
            dsp.setTempoLocked(intent.getBooleanExtra(EXTRA_CLOCK_LOCKED, false));
            dsp.setTempoTrim(intent.getFloatExtra(EXTRA_CLOCK_TRIM_BPM, 0f));
            dsp.setEnabled(intent.getBooleanExtra(EXTRA_ENABLED, false));
            dsp.setFxLevel(intent.getFloatExtra(EXTRA_FX_LEVEL, 0.55f));
            dsp.setFxSub1(intent.getFloatExtra(EXTRA_FX_SUB1, 0.50f));
            dsp.setFxSub2(intent.getFloatExtra(EXTRA_FX_SUB2, 0.50f));
            dsp.setMode(intent.getIntExtra(EXTRA_MODE, -1));
            dsp.setIsolateMode(intent.getIntExtra(EXTRA_ISOLATE_MODE, -1));
            dsp.setIsolateBand(0, intent.getFloatExtra(EXTRA_LOW, 0f));
            dsp.setIsolateBand(1, intent.getFloatExtra(EXTRA_MID, 0f));
            dsp.setIsolateBand(2, intent.getFloatExtra(EXTRA_HIGH, 0f));
            dsp.setXpadRoll(intent.getBooleanExtra(EXTRA_XPAD_ROLL, false));
            dsp.setXpadHold(intent.getBooleanExtra(EXTRA_XPAD_HOLD, false));
            dsp.setXpadTouch(false, intent.getIntExtra(EXTRA_XPAD_DIVISION, 2));
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            try {
                MediaProjectionManager mgr = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                projection = mgr.getMediaProjection(code, data);
                if (projection == null) throw new IllegalStateException("MediaProjection nulla");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { stopEngine(); stopSelf(); }
                }, null);
                startEngine();
            } catch (Exception e) {
                stopEngine(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void startEngine() throws Exception {
        final int rate = 48000;
        final int inMask = AudioFormat.CHANNEL_IN_STEREO;
        final int outMask = AudioFormat.CHANNEL_OUT_STEREO;
        AudioFormat inFormat = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(inMask).build();
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).build();
        int minIn = AudioRecord.getMinBufferSize(rate, inMask, AudioFormat.ENCODING_PCM_16BIT);
        int inBytes = Math.max(minIn * 2, 8192);
        recorder = new AudioRecord.Builder().setAudioFormat(inFormat).setBufferSizeInBytes(inBytes).setAudioPlaybackCaptureConfig(config).build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord non inizializzato");

        AudioFormat outFormat = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(outMask).build();
        int minOut = AudioTrack.getMinBufferSize(rate, outMask, AudioFormat.ENCODING_PCM_16BIT);
        player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(outFormat).setBufferSizeInBytes(Math.max(minOut * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        if (player.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack non inizializzato");

        running.set(true);
        recorder.startRecording();
        player.play();
        audioThread = new Thread(() -> audioLoop(), "RMX-Audio-DSP");
        audioThread.start();
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] pcm = new short[1024];
        long lastTempoBroadcast = 0L;
        try {
            while (running.get()) {
                int n = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) { if (n == AudioRecord.ERROR_DEAD_OBJECT) break; continue; }
                int usable = n - (n % 2);
                dsp.process(pcm, usable);
                long now = SystemClock.uptimeMillis();
                if (now - lastTempoBroadcast >= 500L) {
                    Intent tempo = new Intent(ACTION_TEMPO).setPackage(getPackageName());
                    tempo.putExtra(EXTRA_BPM, dsp.getEstimatedBpm());
                    tempo.putExtra(EXTRA_CLOCK_LOCKED, dsp.isTempoLocked());
                    tempo.putExtra(EXTRA_CLOCK_TRIM_BPM, dsp.getTempoTrimBpm());
                    sendBroadcast(tempo);
                    lastTempoBroadcast = now;
                }
                int offset = 0;
                while (offset < usable && running.get()) {
                    int written = player.write(pcm, offset, usable - offset, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) break;
                    offset += written;
                }
            }
        } catch (Exception ignored) {
        } finally {
            running.set(false);
            releaseAudio();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private synchronized void stopEngine() {
        running.set(false);
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} }
        if (player != null) { try { player.pause(); player.flush(); player.stop(); } catch (Exception ignored) {} }
        MediaProjection oldProjection = projection;
        projection = null;
        if (oldProjection != null) { try { oldProjection.stop(); } catch (Exception ignored) {} }
        Thread t = audioThread;
        if (t == null || !t.isAlive() || t == Thread.currentThread()) releaseAudio();
    }
    private synchronized void releaseAudio() {
        if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "RMX audio capture", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }
    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, CHANNEL).setContentTitle("RMX-1000 Audio Test").setContentText("Acquisizione audio in corso").setSmallIcon(android.R.drawable.ic_media_play).build();
        return new Notification.Builder(this).setContentTitle("RMX-1000 Audio Test").setContentText("Acquisizione audio in corso").setSmallIcon(android.R.drawable.ic_media_play).build();
    }
    @Override public void onDestroy() { stopEngine(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
