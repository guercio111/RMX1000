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
    private final Button[] sceneFxButtons = new Button[10];
    private TextView sceneHint;
    private final Button[] isolateModeButtons = new Button[4];
    private TextView rangeNote;
    private boolean fxEnabled = false;
    private boolean captureStarted = false;
    private int fxMode = -1;
    private int isolateMode = -1;
    private final float[] isolate = {0f, 0f, 0f};
    private float fxLevel = 0.55f;
    private float fxSub1 = 0.50f;
    private float fxSub2 = 0.50f;

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
        title.setText("RMX-1000  /  AUDIO ENGINE TEST 1.0.1");
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

        TextView sceneTitle = new TextView(this);
        sceneTitle.setText("SCENE FX");
        sceneTitle.setTextColor(0xFFFFC928);
        sceneTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        sceneTitle.setGravity(Gravity.CENTER);
        transport.addView(sceneTitle, new LinearLayout.LayoutParams(-1, dp(30)));

        String[] sceneNames = {"BPF ECHO", "ECHO", "NOISE", "SPIRAL UP", "REVERB UP",
                "HPF ECHO", "LPF ECHO", "CRUSH ECHO", "SPIRAL DOWN", "REVERB DOWN"};
        LinearLayout sceneGrid = new LinearLayout(this);
        sceneGrid.setOrientation(LinearLayout.HORIZONTAL);
        sceneGrid.setGravity(Gravity.CENTER);
        transport.addView(sceneGrid, new LinearLayout.LayoutParams(-1, dp(202)));
        for (int column = 0; column < 2; column++) {
            LinearLayout buttonColumn = new LinearLayout(this);
            buttonColumn.setOrientation(LinearLayout.VERTICAL);
            buttonColumn.setGravity(Gravity.CENTER);
            sceneGrid.addView(buttonColumn, new LinearLayout.LayoutParams(0, -1, 1f));
            for (int row = 0; row < 5; row++) {
                final int selected = column * 5 + row;
                Button selector = makeButton(sceneNames[selected], Color.rgb(70, 73, 80));
                selector.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                selector.setOnClickListener(v -> selectSceneFx(selected));
                sceneFxButtons[selected] = selector;
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(36));
                bp.leftMargin = dp(2);
                bp.rightMargin = dp(2);
                bp.topMargin = dp(2);
                bp.bottomMargin = dp(2);
                buttonColumn.addView(selector, bp);
            }
        }
        updateSceneButtons();

        sceneHint = new TextView(this);
        sceneHint.setText("SELEZIONA UN EFFETTO SCENE FX");
        sceneHint.setTextColor(Color.LTGRAY);
        sceneHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sceneHint.setGravity(Gravity.CENTER);
        transport.addView(sceneHint, new LinearLayout.LayoutParams(-1, dp(26)));

        LinearLayout fxControls = new LinearLayout(this);
        fxControls.setOrientation(LinearLayout.HORIZONTAL);
        fxControls.setGravity(Gravity.CENTER);
        transport.addView(fxControls, new LinearLayout.LayoutParams(-1, dp(126)));

        RotaryKnob levelKnob = new RotaryKnob(this, "SCENE FX", 0f, 1f, "%", value -> {
            fxLevel = value;
            sendFxParameter(0, value);
        });
        levelKnob.setValue(fxLevel);
        fxControls.addView(levelKnob, new LinearLayout.LayoutParams(0, -1, 1f));

        RotaryKnob sub1Knob = new RotaryKnob(this, "SUB 1", 0f, 1f, "%", value -> {
            fxSub1 = value;
            sendFxParameter(1, value);
        });
        sub1Knob.setValue(fxSub1);
        fxControls.addView(sub1Knob, new LinearLayout.LayoutParams(0, -1, 1f));

        RotaryKnob sub2Knob = new RotaryKnob(this, "SUB 2", 0f, 1f, "%", value -> {
            fxSub2 = value;
            sendFxParameter(2, value);
        });
        sub2Knob.setValue(fxSub2);
        fxControls.addView(sub2Knob, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout isolatePanel = new LinearLayout(this);
        isolatePanel.setOrientation(LinearLayout.VERTICAL);
        isolatePanel.setGravity(Gravity.CENTER);
        isolatePanel.setBackgroundColor(Color.rgb(31, 33, 37));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -1, 1f);
        ip.leftMargin = dp(14);
        content.addView(isolatePanel, ip);

        TextView isolateTitle = new TextView(this);
        isolateTitle.setText("ISOLATE FX");
        isolateTitle.setTextColor(0xFFFFC928);
        isolateTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        isolateTitle.setGravity(Gravity.CENTER);
        isolatePanel.addView(isolateTitle, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout isolateSelectRow = new LinearLayout(this);
        isolateSelectRow.setOrientation(LinearLayout.HORIZONTAL);
        isolateSelectRow.setGravity(Gravity.CENTER);
        isolatePanel.addView(isolateSelectRow, new LinearLayout.LayoutParams(-1, dp(48)));
        String[] isolateNames = {"ISOLATOR", "CUT / ADD", "TRANS / ROLL", "GATE / DRIVE"};
        for (int mode = 0; mode < isolateNames.length; mode++) {
            final int selected = mode;
            Button selector = makeButton(isolateNames[mode], Color.rgb(70, 73, 80));
            selector.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            selector.setOnClickListener(v -> selectIsolateMode(selected));
            isolateModeButtons[mode] = selector;
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            bp.leftMargin = dp(3);
            bp.rightMargin = dp(3);
            isolateSelectRow.addView(selector, bp);
        }
        updateIsolateButtons();

        rangeNote = new TextView(this);
        updateIsolateHint();
        rangeNote.setTextColor(Color.LTGRAY);
        rangeNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        rangeNote.setGravity(Gravity.CENTER);
        isolatePanel.addView(rangeNote, new LinearLayout.LayoutParams(-1, dp(30)));

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
            knobs.addView(knob, new LinearLayout.LayoutParams(0, dp(168), 1f));
        }

        content.addView(new LinearLayout(this), new LinearLayout.LayoutParams(0, 1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView note = new TextView(this);
        note.setText("Audio capture prototype: Android processes a copy; original audio may remain audible. Scene FX and Isolate FX use original DSP approximations.");
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

    private void sendFxParameter(int parameter, float value) {
        if (!captureStarted) return;
        Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_FX_PARAM);
        i.putExtra(CaptureService.EXTRA_FX_PARAM, parameter);
        i.putExtra(CaptureService.EXTRA_VALUE, value);
        startService(i);
    }

    private void selectSceneFx(int selected) {
        fxMode = (fxMode == selected) ? -1 : selected;
        fxEnabled = fxMode >= 0;
        updateSceneButtons();
        updateSceneHint();
        if (captureStarted) {
            Intent enabled = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_ECHO);
            enabled.putExtra(CaptureService.EXTRA_ENABLED, fxEnabled);
            startService(enabled);
            if (fxEnabled) {
                Intent mode = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_MODE);
                mode.putExtra(CaptureService.EXTRA_MODE, fxMode);
                startService(mode);
            }
        }
    }

    private void updateSceneButtons() {
        for (int i = 0; i < sceneFxButtons.length; i++) {
            Button button = sceneFxButtons[i];
            if (button != null) {
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        fxMode == i ? Color.rgb(35, 145, 80) : Color.rgb(70, 73, 80)));
            }
        }
    }

    private void updateSceneHint() {
        if (sceneHint == null) return;
        String[] descriptions = {
                "BPF ECHO  •  SUB 1: DELAY  /  SUB 2: BANDA",
                "ECHO  •  SUB 1: DELAY  /  SUB 2: FEEDBACK",
                "NOISE  •  SUB 1: QUANTITÀ  /  SUB 2: COLORE",
                "SPIRAL UP  •  SUB 1: TEMPO  /  SUB 2: MODULAZIONE",
                "REVERB UP  •  SUB 1: CODA  /  SUB 2: TONO",
                "HPF ECHO  •  SUB 1: DELAY  /  SUB 2: TAGLIO",
                "LPF ECHO  •  SUB 1: DELAY  /  SUB 2: TAGLIO",
                "CRUSH ECHO  •  SUB 1: DELAY  /  SUB 2: CRUSH",
                "SPIRAL DOWN  •  SUB 1: TEMPO  /  SUB 2: MODULAZIONE",
                "REVERB DOWN  •  SUB 1: CODA  /  SUB 2: TONO"
        };
        sceneHint.setText(fxMode < 0 ? "SCENE FX OFF" : descriptions[fxMode]);
    }

    private void selectIsolateMode(int selected) {
        isolateMode = (isolateMode == selected) ? -1 : selected;
        updateIsolateButtons();
        updateIsolateHint();
        if (captureStarted) {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_ISOLATE_MODE);
            i.putExtra(CaptureService.EXTRA_ISOLATE_MODE, isolateMode);
            startService(i);
        }
    }

    private void updateIsolateButtons() {
        for (int i = 0; i < isolateModeButtons.length; i++) {
            Button button = isolateModeButtons[i];
            if (button != null) {
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        isolateMode == i ? Color.rgb(35, 145, 80) : Color.rgb(70, 73, 80)));
            }
        }
    }

    private void updateIsolateHint() {
        if (rangeNote == null) return;
        String hint;
        switch (isolateMode) {
            case DspProcessor.ISOLATE_ISOLATOR:
                hint = "CENTRO = NEUTRO  |  SINISTRA = RIDUCE  |  DESTRA = AUMENTA";
                break;
            case DspProcessor.ISOLATE_CUT_ADD:
                hint = "CENTRO = NEUTRO  |  SINISTRA = CUT  |  DESTRA = DELAY 1/8";
                break;
            case DspProcessor.ISOLATE_TRANS_ROLL:
                hint = "CENTRO = NEUTRO  |  SINISTRA = TRANS  |  DESTRA = ROLL";
                break;
            case DspProcessor.ISOLATE_GATE_DRIVE:
                hint = "CENTRO = NEUTRO  |  SINISTRA = GATE  |  DESTRA = DRIVE";
                break;
            default:
                hint = "SELEZIONA UN EFFETTO ISOLATE FX";
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
        service.putExtra(CaptureService.EXTRA_FX_LEVEL, fxLevel);
        service.putExtra(CaptureService.EXTRA_FX_SUB1, fxSub1);
        service.putExtra(CaptureService.EXTRA_FX_SUB2, fxSub2);
        service.putExtra(CaptureService.EXTRA_MODE, fxMode);
        service.putExtra(CaptureService.EXTRA_ISOLATE_MODE, isolateMode);
        service.putExtra(CaptureService.EXTRA_LOW, isolate[0]);
        service.putExtra(CaptureService.EXTRA_MID, isolate[1]);
        service.putExtra(CaptureService.EXTRA_HIGH, isolate[2]);
        try {
            startForegroundService(service);
            captureStarted = true;
            status.setText("Cattura avviata. Seleziona SCENE FX o ISOLATE FX.");
        } catch (Exception e) {
            captureStarted = false;
            status.setText("Avvio non riuscito: " + e.getClass().getSimpleName());
        }
    }
}
