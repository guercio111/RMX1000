package it.rmx1000.test;

import android.app.Activity;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import android.graphics.Color;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE=7001;
    private MediaProjectionManager projectionManager;
    private TextView status,bpm,clock,confidence,beat;
    private float offset=0f;
    private boolean receiverRegistered=false;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(CaptureService.BPM.equals(i.getAction())){
                float detected=i.getFloatExtra("detected",0),out=i.getFloatExtra("clock",0),conf=i.getFloatExtra("confidence",0);
                boolean locked=i.getBooleanExtra("locked",false);
                bpm.setText(detected>0?String.format(Locale.US,"%.1f",detected):"—");
                clock.setText(out>0?String.format(Locale.US,"%.1f BPM",out):"CLOCK —");
                confidence.setText(String.format(Locale.US,"AFFIDABILITÀ  %d%%   •   %s",(int)(conf*100),locked?"LOCK":"RICERCA"));
            }else if(CaptureService.STATUS.equals(i.getAction()))status.setText(i.getStringExtra("message"));
            else if(CaptureService.BEAT.equals(i.getAction())){beat.setText("●");beat.setTextColor(0xFF39E879);beat.postDelayed(()->beat.setTextColor(0xFF56606B),110);}
        }
    };
    @Override public void onCreate(Bundle b){super.onCreate(b);projectionManager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);build();}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(24),dp(14),dp(24),dp(14));root.setBackgroundColor(Color.rgb(19,21,25));
        TextView title=text("BPM  /  MASTER CLOCK",26,Color.WHITE);root.addView(title);
        status=text("Pronto. Avvia la musica e poi la cattura.",13,0xFFB9BEC7);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(38));status.setGravity(Gravity.CENTER);root.addView(status,sp);
        LinearLayout values=new LinearLayout(this);values.setGravity(Gravity.CENTER);values.setOrientation(LinearLayout.HORIZONTAL);root.addView(values,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout left=panel();LinearLayout right=panel();values.addView(left,new LinearLayout.LayoutParams(0,-1,1));values.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        left.addView(text("BPM RILEVATO",15,0xFFFFC928));bpm=text("—",62,Color.WHITE);left.addView(bpm);confidence=text("AFFIDABILITÀ —",14,0xFFB9BEC7);left.addView(confidence);
        right.addView(text("MASTER CLOCK",15,0xFF39E879));clock=text("CLOCK —",38,Color.WHITE);right.addView(clock);beat=text("●",48,0xFF56606B);right.addView(beat);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);root.addView(controls,new LinearLayout.LayoutParams(-1,dp(58)));
        addButton(controls,"− 1 BPM",()->changeOffset(-1));addButton(controls,"SYNC",()->command(CaptureService.SYNC));addButton(controls,"+ 1 BPM",()->changeOffset(1));
        LinearLayout transport=new LinearLayout(this);transport.setGravity(Gravity.CENTER);root.addView(transport,new LinearLayout.LayoutParams(-1,dp(56)));
        addButton(transport,"START CAPTURE",()->requestCapture());addButton(transport,"STOP",()->command(CaptureService.STOP));
        TextView foot=text("CORREZIONE MANUALE",12,0xFFB9BEC7);root.addView(foot);
        TextView range=text("−10 BPM                      0                      +10 BPM",13,0xFFFFC928);root.addView(range);
        setContentView(root);
    }
    private LinearLayout panel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setGravity(Gravity.CENTER);p.setBackgroundColor(0xFF252930);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(8),dp(8),dp(8),dp(8));return p;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    private void addButton(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setAllCaps(false);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(label.equals("SYNC")?0xFF247B4B:0xFF444B55));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(4),0,dp(4),0);row.addView(b,p);}
    private void changeOffset(float d){offset=Math.max(-10,Math.min(10,offset+d));Intent i=new Intent(this,CaptureService.class).setAction(CaptureService.OFFSET).putExtra(CaptureService.EXTRA_OFFSET,offset);startService(i);status.setText(String.format(Locale.US,"Correzione clock: %+.1f BPM",offset));}
    private void command(String action){startService(new Intent(this,CaptureService.class).setAction(action));}
    private void requestCapture(){startActivityForResult(projectionManager.createScreenCaptureIntent(),REQUEST_CAPTURE);}
    @Override protected void onActivityResult(int r,int code,Intent data){super.onActivityResult(r,code,data);if(r!=REQUEST_CAPTURE)return;if(code!=RESULT_OK||data==null){status.setText("Cattura non autorizzata");return;}Intent s=new Intent(this,CaptureService.class).setAction(CaptureService.START).putExtra(CaptureService.EXTRA_CODE,code).putExtra(CaptureService.EXTRA_DATA,data);startForegroundService(s);}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter();f.addAction(CaptureService.BPM);f.addAction(CaptureService.BEAT);f.addAction(CaptureService.STATUS);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);receiverRegistered=true;}
    @Override protected void onStop(){if(receiverRegistered){unregisterReceiver(receiver);receiverRegistered=false;}super.onStop();}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}