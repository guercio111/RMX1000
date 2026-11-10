package it.rmx1000.test;

import android.app.Activity;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.*;
import android.graphics.Color;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE=7001;
    private MediaProjectionManager projectionManager;
    private TextView status,bpm,clock,confidence,beat;
    private float offset=0f;
    private TextView sendLabel;
    private RotarySend sendControl;
    private boolean receiverRegistered=false;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(CaptureService.BPM.equals(i.getAction())){
                float detected=i.getFloatExtra("detected",0),out=i.getFloatExtra("clock",0),conf=i.getFloatExtra("confidence",0);
                boolean locked=i.getBooleanExtra("locked",false);
                bpm.setText(detected>0?String.format(Locale.US,"%.1f",detected):"—");
                clock.setText(out>0?String.format(Locale.US,"%.1f BPM",out):"CLOCK —");
                confidence.setText(String.format(Locale.US,"AFFIDABILITÀ  %d%%   •   %s",(int)(conf*100),locked?"LOCK":"RICERCA"));
            }else if(CaptureService.STATUS.equals(i.getAction())){
                String message=i.getStringExtra("message");status.setText(message);
                if(message!=null&&(message.contains("arrestata")||message.contains("fallito"))){setSendUi(0);}
            }else if(CaptureService.BEAT.equals(i.getAction())){beat.setText("●");beat.setTextColor(0xFF39E879);beat.postDelayed(()->beat.setTextColor(0xFF56606B),110);}
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
        addButton(transport,"START CAPTURE",()->requestCapture());addButton(transport,"STOP",()->{setSendUi(0);command(CaptureService.STOP);});
        sendLabel=text("ECHO SEND  0%  ·  SOLO AUDIO ORIGINALE",13,0xFFFFC928);root.addView(sendLabel);
        sendControl=new RotarySend(this,value->{
            sendLabel.setText(value==0?"ECHO SEND  0%  ·  SOLO AUDIO ORIGINALE":String.format(Locale.US,"ECHO SEND  %d%%",value));
            sendSend(value);
        });
        LinearLayout sendRow=new LinearLayout(this);sendRow.setGravity(Gravity.CENTER);sendRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(sendRow,new LinearLayout.LayoutParams(-1,dp(112)));
        sendRow.addView(sendControl,new LinearLayout.LayoutParams(dp(104),dp(104)));
        TextView foot=text("CORREZIONE MANUALE",12,0xFFB9BEC7);root.addView(foot);
        TextView range=text("−10 BPM                      0                      +10 BPM",13,0xFFFFC928);root.addView(range);
        setContentView(root);
    }
    private void setSendUi(int value){
        if(sendControl!=null)sendControl.setValue(value);
        if(value==0&&sendLabel!=null)sendLabel.setText("ECHO SEND  0%  ·  SOLO AUDIO ORIGINALE");
    }
    private void sendSend(int value){
        Intent i=new Intent(this,CaptureService.class).setAction(CaptureService.FX).putExtra(CaptureService.EXTRA_FX_SEND,value/100f);
        startService(i);
    }
    private interface SendListener { void changed(int value); }
    private final class RotarySend extends View {
        private final Paint paint=new Paint(3);
        private final RectF arc=new RectF();
        private final SendListener listener;
        private int value=0;
        private float downY,lastY;
        RotarySend(Context context,SendListener listener){super(context);this.listener=listener;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        void setValue(int v){value=Math.max(0,Math.min(100,v));invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)*0.76f;
            arc.set(cx-r,cy-r,cx+r,cy+r);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(7));paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFF454C56);canvas.drawArc(arc,135,270,false,paint);
            if(value>0){paint.setColor(0xFFFFC928);canvas.drawArc(arc,135,270f*value/100f,false,paint);}
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xFF252B33);canvas.drawCircle(cx,cy,r*0.72f,paint);
            double a=Math.toRadians(135+270.0*value/100.0);
            paint.setColor(0xFFFFC928);canvas.drawCircle(cx+(float)Math.cos(a)*r*0.9f,cy+(float)Math.sin(a)*r*0.9f,dp(4),paint);
            paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(17));paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            canvas.drawText(value+"%",cx,cy+dp(6),paint);
            paint.setColor(0xFFB9BEC7);paint.setTextSize(dp(9));paint.setTypeface(android.graphics.Typeface.DEFAULT);
            canvas.drawText("SEND",cx,cy+r*0.48f,paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()==MotionEvent.ACTION_DOWN){downY=lastY=event.getY();return true;}
            if(event.getAction()==MotionEvent.ACTION_MOVE){
                float dy=lastY-event.getY();lastY=event.getY();
                if(Math.abs(event.getY()-downY)>dp(1)){
                    value=Math.max(0,Math.min(100,value+Math.round(dy*1.2f)));
                    invalidate();listener.changed(value);
                }
                return true;
            }
            return event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL;
        }
    }
    private LinearLayout panel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setGravity(Gravity.CENTER);p.setBackgroundColor(0xFF252930);return p;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    private Button addButton(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setAllCaps(false);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(label.equals("SYNC")?0xFF247B4B:0xFF444B55));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(4),0,dp(4),0);row.addView(b,p);return b;}
    private void changeOffset(float d){offset=Math.max(-10,Math.min(10,offset+d));Intent i=new Intent(this,CaptureService.class).setAction(CaptureService.OFFSET).putExtra(CaptureService.EXTRA_OFFSET,offset);startService(i);status.setText(String.format(Locale.US,"Correzione clock: %+.1f BPM",offset));}
    private void command(String action){startService(new Intent(this,CaptureService.class).setAction(action));}
    private void requestCapture(){setSendUi(0);startActivityForResult(projectionManager.createScreenCaptureIntent(),REQUEST_CAPTURE);}
    @Override protected void onActivityResult(int r,int code,Intent data){super.onActivityResult(r,code,data);if(r!=REQUEST_CAPTURE)return;if(code!=RESULT_OK||data==null){status.setText("Cattura non autorizzata");return;}Intent s=new Intent(this,CaptureService.class).setAction(CaptureService.START).putExtra(CaptureService.EXTRA_CODE,code).putExtra(CaptureService.EXTRA_DATA,data);startForegroundService(s);}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter();f.addAction(CaptureService.BPM);f.addAction(CaptureService.BEAT);f.addAction(CaptureService.STATUS);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);receiverRegistered=true;}
    @Override protected void onStop(){if(receiverRegistered){unregisterReceiver(receiver);receiverRegistered=false;}super.onStop();}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}