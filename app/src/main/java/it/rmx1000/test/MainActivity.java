package it.rmx1000.test;

import android.app.Activity;
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
    private Button echoButton;
    private Button modeButton;
    private boolean echoEnabled = true;
    private int fxMode = DspProcessor.MODE_ECHO;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(20), dp(28), dp(20));
        root.setBackgroundColor(Color.rgb(24, 25, 28));

        TextView title = new TextView(this);
        title.setText("RMX-1000  /  AUDIO ENGINE TEST 0.2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(54)));

        status = new TextView(this);
        status.setText("Pronto. Avvia la cattura mentre Morphe sta riproducendo musica.");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(74));
        sp.bottomMargin = dp(12);
        root.addView(status, sp);

        Button start = makeButton("START AUDIO CAPTURE", Color.rgb(35, 145, 80));
        start.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            } else {
                requestCaptureConsent();
            }
        });
        root.addView(start, buttonParams());

        Button stop = makeButton("STOP", Color.rgb(165, 48, 48));
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
            startService(i);
            status.setText("Arresto richiesto.");
        });
        root.addView(stop, buttonParams());

        echoButton = makeButton("FX  ON", Color.rgb(190, 145, 25));
        echoButton.setOnClickListener(v -> {
            echoEnabled = !echoEnabled;
            echoButton.setText(echoEnabled ? "FX  ON" : "FX  OFF");
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_ECHO);
            i.putExtra(CaptureService.EXTRA_ENABLED, echoEnabled);
            startService(i);
        });
        root.addView(echoButton, buttonParams());

        modeButton = makeButton("SCENE FX  /  ECHO", Color.rgb(40, 105, 165));
        modeButton.setOnClickListener(v -> {
            fxMode = (fxMode == DspProcessor.MODE_ECHO) ? DspProcessor.MODE_REVERB : DspProcessor.MODE_ECHO;
            modeButton.setText(fxMode == DspProcessor.MODE_ECHO ? "SCENE FX  /  ECHO" : "SCENE FX  /  REVERB");
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_MODE);
            i.putExtra(CaptureService.EXTRA_MODE, fxMode);
            startService(i);
        });
        root.addView(modeButton, buttonParams());

        TextView note = new TextView(this);
        note.setText("TEST TECNICO 0.2: cattura una copia dell'audio. Il segnale originale potrebbe restare udibile. Scene FX sperimentali: Echo e Reverb.");
        note.setTextColor(Color.GRAY);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(70));
        np.topMargin = dp(12);
        root.addView(note, np);
        setContentView(root);
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        return b;
    }
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(360), dp(58));
        p.topMargin = dp(5); p.bottomMargin = dp(5);
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
        service.putExtra(CaptureService.EXTRA_ENABLED, echoEnabled);
        service.putExtra(CaptureService.EXTRA_MODE, fxMode);
        try {
            startForegroundService(service);
            status.setText("Autorizzazione ricevuta. Avvio motore audio…");
        } catch (Exception e) {
            status.setText("Avvio non riuscito: " + e.getClass().getSimpleName());
        }
    }
}
