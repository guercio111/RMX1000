package it.rmx1000.test;

/**
 * Original real-time DSP approximations for an RMX-1000-inspired audio test.
 * No Pioneer firmware, proprietary code or factory DSP coefficients are used.
 */
public final class DspProcessor {
    public static final int MODE_ECHO = 0;
    public static final int MODE_REVERB = 1;
    public static final int ISOLATE_ISOLATOR = 0;
    public static final int ISOLATE_CUT_ADD = 1;
    public static final int ISOLATE_TRANS_ROLL = 2;
    public static final int ISOLATE_GATE_DRIVE = 3;

    private static final float LIMIT = 0.96f;
    private volatile int mode = MODE_ECHO;
    private volatile boolean enabled = true;
    private volatile int isolateMode = ISOLATE_ISOLATOR;
    private final float[] echo = new float[48000 / 4 * 2];
    private int echoPosition;

    private final int[] lengths = {1493, 1601, 1747, 1867};
    private final float[][] reverbLines = new float[4][];
    private final int[] reverbPositions = new int[4];
    private float lpLeft, lpRight;

    // Complementary crossovers: LOW + MID + HIGH reconstruct the input.
    private static final float LOW_ALPHA = 0.0323f;
    private static final float HIGH_ALPHA = 0.279f;
    private float lowL, lowR, lowMidL, lowMidR;
    private volatile float lowTargetGain = 1f, midTargetGain = 1f, highTargetGain = 1f;
    private float lowGain = 1f, midGain = 1f, highGain = 1f;

    // Approximate 1/8-beat repeat at 120 BPM (62.5 ms); editable timing comes later.
    private static final int REPEAT_SAMPLES = 3000;
    private final float[][] repeatL = new float[3][REPEAT_SAMPLES];
    private final float[][] repeatR = new float[3][REPEAT_SAMPLES];
    private int repeatPosition;
    private long frameCounter;
    private final float[] envelopes = new float[3];

    public DspProcessor() {
        for (int i = 0; i < lengths.length; i++) reverbLines[i] = new float[lengths[i] * 2];
    }

    public void setMode(int value) {
        if (value == MODE_ECHO || value == MODE_REVERB) mode = value;
    }
    public void setIsolateMode(int value) {
        if (value >= ISOLATE_ISOLATOR && value <= ISOLATE_GATE_DRIVE) isolateMode = value;
    }
    public int getMode() { return mode; }
    public int getIsolateMode() { return isolateMode; }
    public void setEnabled(boolean value) { enabled = value; }

    /** band: 0=LOW, 1=MID, 2=HIGH; setting -1..+1, center is neutral. */
    public void setIsolateBand(int band, float setting) {
        float v = Math.max(-1f, Math.min(1f, setting));
        float gain = bandGain(v);
        if (band == 0) lowTargetGain = gain;
        else if (band == 1) midTargetGain = gain;
        else if (band == 2) highTargetGain = gain;
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
            float outL = left, outR = right;

            if (enabled && mode == MODE_ECHO) {
                float wetL = echo[echoPosition], wetR = echo[echoPosition + 1];
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
                    float a = reverbLines[line][p], b = reverbLines[line][p + 1];
                    sumL += a; sumR += b;
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

            lowGain += (lowTargetGain - lowGain) * 0.0012f;
            midGain += (midTargetGain - midGain) * 0.0012f;
            highGain += (highTargetGain - highGain) * 0.0012f;

            lowL += (outL - lowL) * LOW_ALPHA;
            lowR += (outR - lowR) * LOW_ALPHA;
            lowMidL += (outL - lowMidL) * HIGH_ALPHA;
            lowMidR += (outR - lowMidR) * HIGH_ALPHA;

            float b0L = lowL, b0R = lowR;
            float b1L = lowMidL - lowL, b1R = lowMidR - lowR;
            float b2L = outL - lowMidL, b2R = outR - lowMidR;

            float[] unused = null; // no per-sample allocations in the audio callback
            float finalL = processBand(0, b0L, lowGain) +
                           processBand(1, b1L, midGain) +
                           processBand(2, b2L, highGain);
            float finalR = processBandRight(0, b0R, lowGain) +
                           processBandRight(1, b1R, midGain) +
                           processBandRight(2, b2R, highGain);

            repeatPosition++;
            if (repeatPosition >= REPEAT_SAMPLES) repeatPosition = 0;
            frameCounter++;
            pcm[i] = (short)(clamp(finalL) * 32767f);
            pcm[i + 1] = (short)(clamp(finalR) * 32767f);
        }
    }

    private float processBand(int band, float input, float gain) {
        float value = input * gain;
        float setting = bandSetting(gain);
        if (isolateMode == ISOLATE_CUT_ADD) {
            if (gain < 1f) value = input * gain;
            else if (gain > 1.0001f) value += repeatL[band][repeatPosition] * Math.min(0.65f, (gain - 1f) * 2.2f);
            repeatL[band][repeatPosition] = input + repeatL[band][repeatPosition] * 0.22f;
        } else if (isolateMode == ISOLATE_TRANS_ROLL) {
            if (gain < 0.999f) {
                long phase = frameCounter % 12000L; // quarter-note at 120 BPM
                float gate = phase < 6000L ? 1f : 0f;
                value *= gate;
            } else if (gain > 1.0001f) {
                value += repeatL[band][repeatPosition] * Math.min(0.8f, (gain - 1f) * 3.0f);
                repeatL[band][repeatPosition] = input + repeatL[band][repeatPosition] * 0.35f;
            }
        } else if (isolateMode == ISOLATE_GATE_DRIVE) {
            if (gain < 0.999f) {
                envelopes[band] += (Math.abs(input) - envelopes[band]) * 0.025f;
                float threshold = 0.012f + (1f - gain) * 0.22f;
                if (envelopes[band] < threshold) value = 0f;
            } else if (gain > 1.0001f) {
                float drive = 1f + (gain - 1f) * 8f;
                value = softClip(value * drive) / softClip(drive);
            }
        }
        return value;
    }

    private float processBandRight(int band, float input, float gain) {
        float value = input * gain;
        if (isolateMode == ISOLATE_CUT_ADD) {
            if (gain > 1.0001f) value += repeatR[band][repeatPosition] * Math.min(0.65f, (gain - 1f) * 2.2f);
            repeatR[band][repeatPosition] = input + repeatR[band][repeatPosition] * 0.22f;
        } else if (isolateMode == ISOLATE_TRANS_ROLL) {
            if (gain < 0.999f) {
                long phase = frameCounter % 12000L;
                value *= phase < 6000L ? 1f : 0f;
            } else if (gain > 1.0001f) {
                value += repeatR[band][repeatPosition] * Math.min(0.8f, (gain - 1f) * 3.0f);
                repeatR[band][repeatPosition] = input + repeatR[band][repeatPosition] * 0.35f;
            }
        } else if (isolateMode == ISOLATE_GATE_DRIVE) {
            if (gain < 0.999f) {
                float threshold = 0.012f + (1f - gain) * 0.22f;
                if (Math.abs(input) < threshold) value = 0f;
            } else if (gain > 1.0001f) {
                float drive = 1f + (gain - 1f) * 8f;
                value = softClip(value * drive) / softClip(drive);
            }
        }
        return value;
    }

    private float bandSetting(float gain) { return gain; }
    private float softClip(float x) { return x / (1f + Math.abs(x)); }
    private float clamp(float value) { return Math.max(-LIMIT, Math.min(LIMIT, value)); }
}
