package it.rmx1000.test;

/**
 * Lightweight stereo Scene FX processor.
 * Algorithms are original approximations, not Pioneer firmware/DSP code.
 */
public final class DspProcessor {
    public static final int MODE_ECHO = 0;
    public static final int MODE_REVERB = 1;

    private static final float LIMIT = 0.96f;
    private final float[][] delayLines;
    private final int[] positions;
    private volatile int mode = MODE_ECHO;
    private volatile boolean enabled = true;
    private int echoPosition;

    // 250 ms stereo echo.
    private final float[] echo = new float[48000 / 4 * 2];

    // Four short stereo feedback lines create a diffuse, room-like tail.
    private final int[] lengths = {1493, 1601, 1747, 1867};
    private final float[][] reverbLines = new float[4][];
    private final int[] reverbPositions = new int[4];
    private float lpLeft;
    private float lpRight;

    public DspProcessor() {
        delayLines = new float[0][];
        positions = new int[0];
        for (int i = 0; i < lengths.length; i++) reverbLines[i] = new float[lengths[i] * 2];
    }

    public void setMode(int value) {
        if (value == MODE_ECHO || value == MODE_REVERB) mode = value;
    }

    public int getMode() { return mode; }
    public void setEnabled(boolean value) { enabled = value; }

    public void process(short[] pcm, int samples) {
        int usable = samples - (samples % 2);
        for (int i = 0; i < usable; i += 2) {
            float left = pcm[i] / 32768.0f;
            float right = pcm[i + 1] / 32768.0f;
            float outL = left;
            float outR = right;

            if (enabled && mode == MODE_ECHO) {
                float wetL = echo[echoPosition];
                float wetR = echo[echoPosition + 1];
                echo[echoPosition] = clamp(left + wetL * 0.38f);
                echo[echoPosition + 1] = clamp(right + wetR * 0.38f);
                outL = left * 0.82f + wetL * 0.48f;
                outR = right * 0.82f + wetR * 0.48f;
                echoPosition += 2;
                if (echoPosition >= echo.length) echoPosition = 0;
            } else if (enabled && mode == MODE_REVERB) {
                float sumL = 0f;
                float sumR = 0f;
                for (int line = 0; line < reverbLines.length; line++) {
                    int p = reverbPositions[line];
                    float a = reverbLines[line][p];
                    float b = reverbLines[line][p + 1];
                    sumL += a;
                    sumR += b;
                    float cross = (line % 2 == 0) ? right : left;
                    reverbLines[line][p] = clamp(left * 0.24f + a * 0.71f + cross * 0.035f);
                    reverbLines[line][p + 1] = clamp(right * 0.24f + b * 0.71f + ((line % 2 == 0) ? left : right) * 0.035f);
                    p += 2;
                    if (p >= reverbLines[line].length) p = 0;
                    reverbPositions[line] = p;
                }
                // Mild damping keeps the tail smooth and avoids a harsh metallic ring.
                lpLeft += (sumL * 0.25f - lpLeft) * 0.42f;
                lpRight += (sumR * 0.25f - lpRight) * 0.42f;
                outL = left * 0.78f + lpLeft * 0.42f;
                outR = right * 0.78f + lpRight * 0.42f;
            }

            pcm[i] = (short)(clamp(outL) * 32767f);
            pcm[i + 1] = (short)(clamp(outR) * 32767f);
        }
    }

    private float clamp(float value) {
        return Math.max(-LIMIT, Math.min(LIMIT, value));
    }
}
