package it.rmx1000.test;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** Touch-operated rotary control, centered at 0 dB. */
public final class RotaryKnob extends View {
    public interface OnValueChanged { void onChanged(float value); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final String label;
    private final OnValueChanged listener;
    private final float minValue;
    private final float maxValue;
    private final String unit;
    private float value = 0f;
    private float downY;
    private float downValue;
    private final float density;

    public RotaryKnob(Context context, String label, OnValueChanged listener) {
        this(context, label, -1f, 1f, "dB", listener);
    }

    public RotaryKnob(Context context, String label, float minValue, float maxValue, String unit, OnValueChanged listener) {
        super(context);
        this.label = label;
        this.listener = listener;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.unit = unit;
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setMinimumHeight((int)dp(150));
    }

    public void setValue(float v) {
        value = Math.max(minValue, Math.min(maxValue, v));
        invalidate();
    }
    public float getValue() { return value; }
    private float dp(float v) { return v * density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.48f;
        float radius = Math.min(getWidth() * 0.34f, getHeight() * 0.31f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xFFFFC928);
        paint.setTextSize(dp(16));
        paint.setFakeBoldText(true);
        c.drawText(label, cx, dp(19), paint);

        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(7));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF55585E);
        c.drawArc(arc, 135, 270, false, paint);
        paint.setColor(0xFF35D477);
        float normalized = ((value - minValue) / (maxValue - minValue)) * 2f - 1f;
        c.drawArc(arc, 270, normalized * 135f, false, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF202328);
        c.drawCircle(cx, cy, radius * 0.78f, paint);
        paint.setColor(0xFF777D86);
        c.drawCircle(cx, cy, radius * 0.68f, paint);

        double angle = Math.toRadians(270 + normalized * 135.0);
        float inner = radius * 0.18f;
        float outer = radius * 0.61f;
        paint.setColor(0xFFFFD34E);
        paint.setStrokeWidth(dp(4));
        c.drawLine(cx + (float)Math.cos(angle) * inner, cy + (float)Math.sin(angle) * inner,
                cx + (float)Math.cos(angle) * outer, cy + (float)Math.sin(angle) * outer, paint);

        paint.setTextSize(dp(14));
        paint.setFakeBoldText(false);
        paint.setColor(ColorForValue());
        String readout;
        if ("dB".equals(unit)) readout = value <= -0.98f ? "CUT" : String.format(Locale.US, "%+.1f dB", value < 0 ? value * 60f : value * 6f);
        else readout = String.format(Locale.US, "%.0f%s", value * 100f, unit);
        c.drawText(readout, cx, getHeight() - dp(8), paint);
    }

    private int ColorForValue() { return value == 0f ? 0xFFE7E7E7 : 0xFF72DFA0; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                downValue = value;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float next = downValue + (downY - event.getY()) / dp(170) * (maxValue - minValue);
                value = Math.max(minValue, Math.min(maxValue, next));
                invalidate();
                if (listener != null) listener.onChanged(value);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                performClick();
                return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
