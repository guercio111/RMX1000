package it.rmx1000.test;

/**
 * Original stereo DSP prototype for RMX-1000-inspired Scene FX and Isolate FX.
 * These algorithms are independent approximations, not Pioneer firmware/DSP code.
 */
public final class DspProcessor {
    public static final int MODE_ECHO = 0;
    public static final int MODE_REVERB = 1;

    private static final float LIMIT = 0.96f;
    private volatile int mode = MODE_ECHO;
    private volatile boolean enabled = true;
    private final float[] echo = new float[48000 / 4 * 2];
    private int echoPosition;

    private final int[] lengths = {1493, 1601, 1747, 1867};
    private final float[][] reverbLines = new float[4][];
    private final int[] reverbPositions = new int[4];
    private float lpLeft;
    private float lpRight;

    // Complementary one-pole crossovers: their three bands sum back to the input.
    private static final float LOW_ALPHA = 0.0323f;  // about 250 Hz at 48 kHz
    private static final float HIGH_ALPHA = 0.279f;  // about 2.5 kHz at 48 kHz
    private float lowL, lowR, lowMidL, lowMidR;
    private volatile float lowTargetGain = 1f, midTargetGain = 1f, highTargetGain = 1f;
    private float lowGain = 1f, midGain = 1f, highGain = 1f;

    public DspProcessor() {
        for (int i = 0; i < lengths.length; i++) reverbLines[i] = new float[lengths[i] * 2];
    }

    public void setMode(int value) {
        if (value == MODE_ECHO || value == MODE_REVERB) mode = value;
    }
    public int getMode() { return mode; }
    public void setEnabled(boolean value) { enabled = value; }

    /** band: 0=LOW, 1=MID, 2=HIGH; setting range -1..+1, center is flat. */
    public void setIsolateBand(int band, float setting) {
        float v = Math.max(-1f, Math.min(1f, setting));
        if (band == 0) lowTargetGain = bandGain(v);
        else if (band == 1) midTargetGain = bandGain(v);
        else if (band == 2) highTargetGain = bandGain(v);
    }

    private float bandGain(float setting) {
        if (setting < 0f) return (float)Math.pow(10.0, (setting * 60.0) / 20.0);
        return (float)Math.pow(10.0, (setting * 6.0) / 20.0);
    }

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
                float sumL = 0f, sumR = 0f;
                for (int line = 0; line < reverbLines.length; line++) {
                    int p = reverbPositions[line];
                    float a = reverbLines[line][p];
                    float b = reverbLines[line][p + 1];
                    sumL += a;
                    sumR += b;
                    float crossL = (line % 2 == 0) ? right : left;
                    float crossR = (line % 2 == 0) ? left : right;
                    reverbLines[line][p] = clamp(left * 0.24f + a * 0.71f + crossL * 0.035f);
                    reverbLines[line][p + 1] = clamp(right * 0.24f + b * 0.71f + crossR * 0.035f);
                    p += 2;
                    if (p >= reverbLines[line].length) p = 0;
                    reverbPositions[line] = p;
                }
                lpLeft += (sumL * 0.25f - lpLeft) * 0.42f;
                lpRight += (sumR * 0.25f - lpRight) * 0.42f;
                outL = left * 0.78f + lpLeft * 0.42f;
                outR = right * 0.78f + lpRight * 0.42f;
            }

            float targetLow = lowTargetGain;
            float targetMid = midTargetGain;
            float targetHigh = highTargetGain;
            // Smooth control movement over a few milliseconds to prevent zipper noise.
            lowGain += (targetLow - lowGain) * 0.0012f;
            midGain += (targetMid - midGain) * 0.0012f;
            highGain += (targetHigh - highGain) * 0.0012f;

            lowL += (outL - lowL) * LOW_ALPHA;
            lowR += (outR - lowR) * LOW_ALPHA;
            lowMidL += (outL - lowMidL) * HIGH_ALPHA;
            lowMidR += (outR - lowMidR) * HIGH_ALPHA;

            float bandLowL = lowL;
            float bandLowR = lowR;
            float bandMidL = lowMidL - lowL;
            float bandMidR = lowMidR - lowR;
            float bandHighL = outL - lowMidL;
            float bandHighR = outR - lowMidR;

            float finalL = bandLowL * lowGain + bandMidL * midGain + bandHighL * highGain;
            float finalR = bandLowR * lowGain + bandMidR * midGain + bandHighR * highGain;
            pcm[i] = (short)(clamp(finalL) * 32767f);
            pcm[i + 1] = (short)(clamp(finalR) * 32767f);
        }
    }

    private float clamp(float value) {
        return Math.max(-LIMIT, Math.min(LIMIT, value));
    }
}
