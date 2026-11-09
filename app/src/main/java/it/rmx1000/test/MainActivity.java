package it.rmx1000.test;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import java.util.Locale;
import android.media.projection.MediaProjectionManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.util.TypedValue;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 7001;
    private static final int REQUEST_AUDIO_PERMISSION = 7002;
    private MediaProjectionManager projectionManager;
    private TextView status;
    private TextView tempoView;
    private boolean tempoReceiverRegistered;
    private final BroadcastReceiver tempoReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (CaptureService.ACTION_TEMPO.equals(intent.getAction()) && tempoView != null) {
                float bpm = intent.getFloatExtra(CaptureService.EXTRA_BPM, 0f);
                tempoView.setText(String.format(Locale.ROOT, "CLOCK  %.1f BPM  •  STIMA", bpm));
            }
        }
    };
    private Button fxButton;
    private Button modeButton;
    private Button isolateModeButton;
    private TextView rangeNote;
    private boolean fxEnabled = true;
    private boolean captureStarted = false;
    private int fxMode = DspProcessor.MODE_ECHO;
    private int isolateMode = DspProcessor.ISOLATE_ISOLATOR;
    private final float[] isolate = {0f, 0f, 0f};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(24, 25, 28));

        TextView title = new TextView(this);
        title.setText("RMX-1000  /  AUDIO ENGINE TEST 0.6.2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        status = new TextView(this);
        status.setText("Pronto. Avvia la cattura mentre Morphe sta riproducendo musica.");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(48)));

        tempoView = new TextView(this);
        tempoView.setText("CLOCK  — BPM");
        tempoView.setTextColor(0xFFFFC928);
        tempoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tempoView.setGravity(Gravity.CENTER);
        root.addView(tempoView, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.VERTICAL);
        transport.setGravity(Gravity.CENTER);
        content.addView(transport, new LinearLayout.LayoutParams(dp(285), -1));

        Button start = makeButton("START AUDIO CAPTURE", Color.rgb(35, 145, 80));
        start.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            } else requestCaptureConsent();
        });
        transport.addView(start, buttonParams());

        Button stop = makeButton("STOP", Color.rgb(165, 48, 48));
        stop.setOnClickListener(v -> {
            captureStarted = false;
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
            startService(i);
            status.setText("Arresto richiesto.");
            tempoView.setText("CLOCK  fermo");
        });
        transport.addView(stop, buttonParams());

        fxButton = makeButton("SCENE FX  ON", Color.rgb(190, 145, 25));
        fxButton.setOnClickListener(v -> {
            fxEnabled = !fxEnabled;
            fxButton.setText(fxEnabled ? "SCENE FX  ON" : "SCENE FX  OFF");
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_ECHO);
            i.putExtra(CaptureService.EXTRA_ENABLED, fxEnabled);
            if (captureStarted) startService(i);
        });
        transport.addView(fxButton, buttonParams());

        modeButton = makeButton("SCENE FX  /  ECHO", Color.rgb(40, 105, 165));
        modeButton.setOnClickListener(v -> {
            fxMode = (fxMode == DspProcessor.MODE_ECHO) ? DspProcessor.MODE_REVERB : DspProcessor.MODE_ECHO;
            modeButton.setText(fxMode == DspProcessor.MODE_ECHO ? "SCENE FX  /  ECHO" : "SCENE FX  /  REVERB");
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_MODE);
            i.putExtra(CaptureService.EXTRA_MODE, fxMode);
            if (captureStarted) startService(i);
        });
        transport.addView(modeButton, buttonParams());

        isolateModeButton = makeButton("ISOLATE FX  /  ISOLATOR", Color.rgb(95, 80, 145));
        isolateModeButton.setOnClickListener(v -> {
            isolateMode = (isolateMode + 1) % 4;
            String[] names = {"ISOLATOR", "CUT / ADD", "TRANS / ROLL", "GATE / DRIVE"};
            isolateModeButton.setText("ISOLATE FX  /  " + names[isolateMode]);
            updateIsolateHint();
            if (captureStarted) {
                Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_ISOLATE_MODE);
                i.putExtra(CaptureService.EXTRA_ISOLATE_MODE, isolateMode);
                startService(i);
            }
        });
        transport.addView(isolateModeButton, buttonParams());

        TextView isolateTitle = new TextView(this);
        isolateTitle.setText("ISOLATE FX");
        isolateTitle.setTextColor(0xFFFFC928);
        isolateTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        isolateTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams itp = new LinearLayout.LayoutParams(-1, dp(38));
        itp.topMargin = dp(3);
        transport.addView(isolateTitle, itp);

        rangeNote = new TextView(this);
        updateIsolateHint();
        rangeNote.setTextColor(Color.LTGRAY);
        rangeNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        rangeNote.setGravity(Gravity.CENTER);
        transport.addView(rangeNote, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout isolatePanel = new LinearLayout(this);
        isolatePanel.setOrientation(LinearLayout.VERTICAL);
        isolatePanel.setGravity(Gravity.CENTER);
        isolatePanel.setBackgroundColor(Color.rgb(31, 33, 37));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -1, 1f);
        ip.leftMargin = dp(14);
        content.addView(isolatePanel, ip);

        TextView isolateHint = new TextView(this);
        isolateHint.setText("THREE-BAND ISOLATE FX  •  TOUCH AND DRAG");
        isolateHint.setTextColor(0xFFBFC4CA);
        isolateHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        isolateHint.setGravity(Gravity.CENTER);
        isolatePanel.addView(isolateHint, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout knobs = new LinearLayout(this);
        knobs.setOrientation(LinearLayout.HORIZONTAL);
        knobs.setGravity(Gravity.CENTER);
        isolatePanel.addView(knobs, new LinearLayout.LayoutParams(-1, 0, 1f));

        String[] labels = {"LOW", "MID", "HIGH"};
        for (int band = 0; band < 3; band++) {
            final int index = band;
            RotaryKnob knob = new RotaryKnob(this, labels[band], value -> {
                isolate[index] = value;
                if (captureStarted) {
                    Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_ISOLATE);
                    i.putExtra(CaptureService.EXTRA_BAND, index);
                    i.putExtra(CaptureService.EXTRA_VALUE, value);
                    startService(i);
                }
            });
            knob.setValue(isolate[band]);
            knobs.addView(knob, new LinearLayout.LayoutParams(0, dp(210), 1f));
        }

        content.addView(new LinearLayout(this), new LinearLayout.LayoutParams(0, 1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView note = new TextView(this);
        note.setText("Audio capture prototype: Android processes a copy; original audio may remain audible. Isolate FX is an original three-band approximation.");
        note.setTextColor(Color.GRAY);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new LinearLayout.LayoutParams(-1, dp(28)));
        setContentView(root);
    }


    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CaptureService.ACTION_TEMPO);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(tempoReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(tempoReceiver, filter);
        tempoReceiverRegistered = true;
    }

    @Override protected void onStop() {
        if (tempoReceiverRegistered) {
            unregisterReceiver(tempoReceiver);
            tempoReceiverRegistered = false;
        }
        super.onStop();
    }

    private void updateIsolateHint() {
        if (rangeNote == null) return;
        String hint;
        switch (isolateMode) {
            case DspProcessor.ISOLATE_CUT_ADD:
                hint = "CENTRO = NEUTRO  |  SINISTRA = CUT  |  DESTRA = REPEAT";
                break;
            case DspProcessor.ISOLATE_TRANS_ROLL:
                hint = "CENTRO = NEUTRO  |  SINISTRA = TAGLIO RITMICO  |  DESTRA = ROLL";
                break;
            case DspProcessor.ISOLATE_GATE_DRIVE:
                hint = "CENTRO = NEUTRO  |  SINISTRA = GATE  |  DESTRA = DRIVE";
                break;
            default:
                hint = "CENTRO = NEUTRO  |  SINISTRA = ATTENUAZIONE  |  DESTRA = BOOST";
                break;
        }
        rangeNote.setText(hint);
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        return b;
    }
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.topMargin = dp(4);
        p.bottomMargin = dp(4);
        return p;
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void requestCaptureConsent() {
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestCaptureConsent();
            else status.setText("Permesso audio negato: impossibile acquisire la riproduzione.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("Cattura non autorizzata.");
            return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_DATA, data);
        service.putExtra(CaptureService.EXTRA_ENABLED, fxEnabled);
        service.putExtra(CaptureService.EXTRA_MODE, fxMode);
        service.putExtra(CaptureService.EXTRA_ISOLATE_MODE, isolateMode);
        service.putExtra(CaptureService.EXTRA_LOW, isolate[0]);
        service.putExtra(CaptureService.EXTRA_MID, isolate[1]);
        service.putExtra(CaptureService.EXTRA_HIGH, isolate[2]);
        try {
            startForegroundService(service);
            captureStarted = true;
            status.setText("Cattura avviata. Regola LOW / MID / HIGH dal centro neutro.");
        } catch (Exception e) {
            captureStarted = false;
            status.setText("Avvio non riuscito: " + e.getClass().getSimpleName());
        }
    }
}
