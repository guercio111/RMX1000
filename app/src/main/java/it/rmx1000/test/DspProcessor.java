package it.rmx1000.test;

/**
 * Original real-time DSP approximations for an RMX-1000-inspired audio test.
 * No Pioneer firmware, proprietary code or factory DSP coefficients are used.
 */
public final class DspProcessor {
    public static final int MODE_BPF_ECHO = 0;
    public static final int MODE_ECHO = 1;
    public static final int MODE_NOISE = 2;
    public static final int MODE_SPIRAL_UP = 3;
    public static final int MODE_REVERB = 4;
    public static final int MODE_HPF_ECHO = 5;
    public static final int MODE_LPF_ECHO = 6;
    public static final int MODE_CRUSH_ECHO = 7;
    public static final int MODE_SPIRAL_DOWN = 8;
    public static final int MODE_REVERB_DOWN = 9;
    public static final int ISOLATE_ISOLATOR = 0;
    public static final int ISOLATE_CUT_ADD = 1;
    public static final int ISOLATE_TRANS_ROLL = 2;
    public static final int ISOLATE_GATE_DRIVE = 3;

    private static final float LIMIT = 0.96f;
    private volatile int mode = -1;
    private volatile boolean enabled = true;
    // FX controls are independent from the adaptive beat tracker.
    private volatile float fxLevel = 0.55f;
    private volatile float fxSub1 = 0.50f;
    private volatile float fxSub2 = 0.50f;
    private volatile int isolateMode = -1;
    private final float[] echo = new float[48000 * 2 * 2];
    private int echoPosition;

    private final int[] lengths = {1493, 1601, 1747, 1867};
    private final float[][] reverbLines = new float[4][];
    private final int[] reverbPositions = new int[4];
    private float lpLeft, lpRight;
    private float sceneLpL, sceneLpR, sceneHpL, sceneHpR;
    private int noiseState = 0x13579BDF;

    // Complementary crossovers: LOW + MID + HIGH reconstruct the input.
    private static final float LOW_ALPHA = 0.0323f;
    private static final float HIGH_ALPHA = 0.279f;
    private float lowL, lowR, lowMidL, lowMidR;
    private volatile float lowTargetGain = 1f, midTargetGain = 1f, highTargetGain = 1f;
    private float lowGain = 1f, midGain = 1f, highGain = 1f;

    // Approximate 1/8-beat repeat at 120 BPM (62.5 ms); editable timing comes later.
    private static final int REPEAT_SAMPLES = 12000; // room for a 1/8-beat delay at 60 BPM
    private final float[][] repeatL = new float[3][REPEAT_SAMPLES];
    private final float[][] repeatR = new float[3][REPEAT_SAMPLES];
    private int repeatPosition;
    private long frameCounter;
    // Adaptive beat tracker driven by low-band transients (kick/bass attacks).
    private long beatSamples = 24000L; // 120 BPM fallback at 48 kHz
    private long rhythmPhase;
    private long lastOnsetFrame = -1L;
    private float bassFast, bassSlow;
    // A short history rejects isolated false tempo estimates without allocating in the audio thread.
    private final long[] tempoCandidates = new long[5];
    private final long[] tempoScratch = new long[5];
    private int tempoCandidateCount;
    private int tempoCandidatePosition;
    // Separate confirmation path for genuine, sustained tempo changes.
    private long challengerBeatSamples;
    private int challengerCount;
    private final float[] envelopesL = new float[3];
    private final float[] envelopesR = new float[3];

    public DspProcessor() {
        for (int i = 0; i < lengths.length; i++) reverbLines[i] = new float[lengths[i] * 2];
    }

    public void setMode(int value) {
        if (value >= -1 && value <= MODE_REVERB_DOWN) mode = value;
    }
    public void setIsolateMode(int value) {
        if (value == -1 || (value >= ISOLATE_ISOLATOR && value <= ISOLATE_GATE_DRIVE)) isolateMode = value;
    }
    public int getMode() { return mode; }
    public int getIsolateMode() { return isolateMode; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setFxLevel(float value) { fxLevel = Math.max(0f, Math.min(1f, value)); }
    public void setFxSub1(float value) { fxSub1 = Math.max(0f, Math.min(1f, value)); }
    public void setFxSub2(float value) { fxSub2 = Math.max(0f, Math.min(1f, value)); }

    /** Current quarter-note estimate, intended for a lightweight UI diagnostic. */
    public float getEstimatedBpm() {
        return 2880000f / Math.max(1L, beatSamples);
    }

    /** Reset rhythmic phase and repeat memory at the beginning of a new capture. */
    public void resetRhythm() {
        frameCounter = 0L;
        rhythmPhase = 0L;
        beatSamples = 24000L;
        lastOnsetFrame = -1L;
        bassFast = bassSlow = 0f;
        tempoCandidateCount = 0;
        tempoCandidatePosition = 0;
        challengerBeatSamples = 0L;
        challengerCount = 0;
        java.util.Arrays.fill(tempoCandidates, 24000L);
        java.util.Arrays.fill(tempoScratch, 24000L);
        repeatPosition = 0;
        for (int band = 0; band < 3; band++) {
            java.util.Arrays.fill(repeatL[band], 0f);
            java.util.Arrays.fill(repeatR[band], 0f);
        }
    }

    /** band: 0=LOW, 1=MID, 2=HIGH; setting -1..+1, center is neutral. */
    public void setIsolateBand(int band, float setting) {
        float v = Math.max(-1f, Math.min(1f, setting));
        float gain = bandGain(v);
        if (band == 0) lowTargetGain = gain;
        else if (band == 1) midTargetGain = gain;
        else if (band == 2) highTargetGain = gain;
    }

    private float bandGain(float setting) {
        // Wide, smooth attenuation for cuts; restrained boost to preserve headroom.
        if (setting < 0f) return (float)Math.pow(10.0, (setting * 48.0) / 20.0);
        return (float)Math.pow(10.0, (setting * 3.0) / 20.0);
    }

    public void process(short[] pcm, int samples) {
        int usable = samples - (samples % 2);
        for (int i = 0; i < usable; i += 2) {
            float left = pcm[i] / 32768.0f;
            float right = pcm[i + 1] / 32768.0f;
            float outL = left, outR = right;

            if (enabled && mode >= 0) {
                boolean reverbMode = mode == MODE_REVERB || mode == MODE_REVERB_DOWN
                        || mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN;
                boolean echoMode = mode == MODE_BPF_ECHO || mode == MODE_ECHO || mode == MODE_HPF_ECHO
                        || mode == MODE_LPF_ECHO || mode == MODE_CRUSH_ECHO
                        || mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN;
                float inputL = left;
                float inputR = right;

                if (mode == MODE_NOISE) {
                    noiseState = noiseState * 1664525 + 1013904223;
                    float noiseL = ((noiseState >>> 8) / 8388608f) - 1f;
                    noiseState = noiseState * 1664525 + 1013904223;
                    float noiseR = ((noiseState >>> 8) / 8388608f) - 1f;
                    float cutoff = 0.025f + fxSub2 * 0.30f;
                    sceneLpL += (noiseL - sceneLpL) * cutoff;
                    sceneLpR += (noiseR - sceneLpR) * cutoff;
                    float bandL = sceneLpL - sceneHpL;
                    float bandR = sceneLpR - sceneHpR;
                    sceneHpL += (bandL - sceneHpL) * 0.018f;
                    sceneHpR += (bandR - sceneHpR) * 0.018f;
                    float noiseAmount = fxSub1 * fxLevel * 0.62f;
                    outL = left + bandL * noiseAmount;
                    outR = right + bandR * noiseAmount;
                } else if (reverbMode) {
                    float sumL = 0f, sumR = 0f;
                    for (int line = 0; line < reverbLines.length; line++) {
                        int p = reverbPositions[line];
                        float a = reverbLines[line][p], b = reverbLines[line][p + 1];
                        sumL += a; sumR += b;
                        float crossL = (line % 2 == 0) ? right : left;
                        float crossR = (line % 2 == 0) ? left : right;
                        float decay = 0.58f + fxSub1 * 0.38f;
                        float feedback = mode == MODE_REVERB_DOWN ? decay * 0.82f : decay;
                        reverbLines[line][p] = clamp(left * 0.24f + a * feedback + crossL * 0.035f);
                        reverbLines[line][p + 1] = clamp(right * 0.24f + b * feedback + crossR * 0.035f);
                        p += 2;
                        if (p >= reverbLines[line].length) p = 0;
                        reverbPositions[line] = p;
                    }
                    float reverbL = sumL * 0.25f;
                    float reverbR = sumR * 0.25f;
                    float damp = 0.04f + fxSub2 * 0.75f;
                    sceneLpL += (reverbL - sceneLpL) * damp;
                    sceneLpR += (reverbR - sceneLpR) * damp;
                    if (mode == MODE_REVERB || mode == MODE_SPIRAL_UP) {
                        // Brighter, rising reverb: emphasize the upper tail.
                        sceneHpL += (sceneLpL - sceneHpL) * 0.045f;
                        sceneHpR += (sceneLpR - sceneHpR) * 0.045f;
                        reverbL = (sceneLpL - sceneHpL) * 1.65f;
                        reverbR = (sceneLpR - sceneHpR) * 1.65f;
                    } else {
                        // Darker, falling reverb: keep the low/mid body and damp highs.
                        reverbL = sceneLpL * 0.92f;
                        reverbR = sceneLpR * 0.92f;
                    }
                    float wet = fxLevel * ((mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN) ? 0.62f : 0.78f);
                    outL = left + reverbL * wet;
                    outR = right + reverbR * wet;
                }

                if (echoMode) {
                    if (mode == MODE_BPF_ECHO) {
                        float lp = 0.035f + fxSub2 * 0.32f;
                        sceneLpL += (inputL - sceneLpL) * lp;
                        sceneLpR += (inputR - sceneLpR) * lp;
                        sceneHpL += (sceneLpL - sceneHpL) * 0.055f;
                        sceneHpR += (sceneLpR - sceneHpR) * 0.055f;
                        inputL = sceneLpL - sceneHpL;
                        inputR = sceneLpR - sceneHpR;
                    } else if (mode == MODE_HPF_ECHO) {
                        float hp = 0.015f + fxSub2 * 0.28f;
                        sceneLpL += (inputL - sceneLpL) * hp;
                        sceneLpR += (inputR - sceneLpR) * hp;
                        inputL -= sceneLpL;
                        inputR -= sceneLpR;
                    } else if (mode == MODE_LPF_ECHO) {
                        float lp = 0.008f + fxSub2 * 0.20f;
                        sceneLpL += (inputL - sceneLpL) * lp;
                        sceneLpR += (inputR - sceneLpR) * lp;
                        inputL = sceneLpL;
                        inputR = sceneLpR;
                    } else if (mode == MODE_CRUSH_ECHO) {
                        int bits = 12 - (int)(fxSub2 * 7f);
                        float steps = (float)(1 << bits);
                        inputL = Math.round(inputL * steps) / steps;
                        inputR = Math.round(inputR * steps) / steps;
                    }

                    int delayFrames = (int)(beatSamples * (0.0625f + fxSub1 * 0.9375f));
                    delayFrames = Math.max(1500, Math.min(echo.length / 2 - 1, delayFrames));
                    int read = echoPosition - delayFrames * 2;
                    float spiralMod = 0f;
                    if (mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN) {
                        double direction = mode == MODE_SPIRAL_UP ? 1.0 : -1.0;
                        spiralMod = (float)Math.sin(frameCounter * 0.00024 * direction) * fxSub2 * 420f;
                        read -= (int)spiralMod * 2;
                    }
                    while (read < 0) read += echo.length;
                    while (read >= echo.length) read -= echo.length;
                    float wetL = echo[read];
                    float wetR = echo[read + 1];
                    float feedback;
                    if (mode == MODE_ECHO) feedback = 0.10f + fxSub2 * 0.78f;
                    else if (mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN) feedback = 0.48f + fxSub1 * 0.24f;
                    else if (mode == MODE_CRUSH_ECHO) feedback = 0.20f + fxSub2 * 0.58f;
                    else feedback = 0.28f;
                    echo[echoPosition] = clamp(inputL + wetL * feedback);
                    echo[echoPosition + 1] = clamp(inputR + wetR * feedback);
                    float pan = (mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN)
                            ? (float)Math.sin(frameCounter * 0.00031) * fxSub2 * 0.22f : 0f;
                    float wet = fxLevel * ((mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN) ? 0.76f : 0.88f);
                    outL += wetL * wet * (1f + pan);
                    outR += wetR * wet * (1f - pan);
                    if (mode == MODE_CRUSH_ECHO) {
                        outL = softClip(outL * 1.7f);
                        outR = softClip(outR * 1.7f);
                    }
                    echoPosition += 2;
                    if (echoPosition >= echo.length) echoPosition = 0;
                }
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

            updateBeatTracker(b0L, b0R);

            float finalL = processBand(0, b0L, lowGain) +
                           processBand(1, b1L, midGain) +
                           processBand(2, b2L, highGain);
            float finalR = processBandRight(0, b0R, lowGain) +
                           processBandRight(1, b1R, midGain) +
                           processBandRight(2, b2R, highGain);

            repeatPosition++;
            if (repeatPosition >= REPEAT_SAMPLES) repeatPosition = 0;
            frameCounter++;
            rhythmPhase++;
            pcm[i] = (short)(softKnee(finalL) * 32767f);
            pcm[i + 1] = (short)(softKnee(finalR) * 32767f);
        }
    }


    /**
     * Estimates quarter-note tempo from low-band attacks. It is intentionally
     * conservative: 60-180 BPM, smoothed updates, and a 120 BPM fallback.
     * This is an audio-onset tracker, not a guaranteed musical beat detector.
     */
    private void updateBeatTracker(float lowLeft, float lowRight) {
        float bass = (Math.abs(lowLeft) + Math.abs(lowRight)) * 0.5f;
        bassFast += (bass - bassFast) * (bass > bassFast ? 0.10f : 0.0025f);
        bassSlow += (bass - bassSlow) * 0.00022f;

        // A long gap usually means silence or a track change. Keep the last BPM
        // as a starting point, but discard interval history tied to the old track.
        if (lastOnsetFrame >= 0L && frameCounter - lastOnsetFrame > 144000L) {
            lastOnsetFrame = -1L;
            tempoCandidateCount = 0;
            tempoCandidatePosition = 0;
            challengerBeatSamples = 0L;
            challengerCount = 0;
        }

        if (bassFast < 0.018f || bassFast < bassSlow * 1.65f) return;
        if (lastOnsetFrame >= 0L && frameCounter - lastOnsetFrame < 8640L) return; // 180 ms guard

        if (lastOnsetFrame >= 0L) {
            long interval = frameCounter - lastOnsetFrame;
            if (interval >= 9000L && interval <= 96000L) {
                long best = beatSamples;
                double bestError = Double.MAX_VALUE;
                for (int multiply = 1; multiply <= 4; multiply++) {
                    for (int divide = 1; divide <= 4; divide++) {
                        long candidate = interval * multiply / divide;
                        if (candidate < 16000L || candidate > 48000L) continue;
                        double error = Math.abs(Math.log((double) candidate / (double) beatSamples));
                        if (error < bestError) {
                            bestError = error;
                            best = candidate;
                        }
                    }
                }

                // Small tempo movements use the stable five-value median.
                // Larger movements must form a consistent three-hit challenger
                // before replacing the old tempo, so one stray kick cannot jump the clock.
                double deviation = Math.abs((double) best - (double) beatSamples) / (double) beatSamples;
                if (deviation > 0.12) {
                    double challengerDeviation = challengerBeatSamples == 0L ? Double.MAX_VALUE
                            : Math.abs((double) best - (double) challengerBeatSamples) / (double) challengerBeatSamples;
                    if (challengerDeviation > 0.08) {
                        challengerBeatSamples = best;
                        challengerCount = 1;
                    } else {
                        challengerBeatSamples += (best - challengerBeatSamples) / 3L;
                        challengerCount++;
                    }

                    if (challengerCount >= 3) {
                        beatSamples = challengerBeatSamples;
                        java.util.Arrays.fill(tempoCandidates, beatSamples);
                        tempoCandidateCount = 1;
                        tempoCandidatePosition = 1;
                        challengerBeatSamples = 0L;
                        challengerCount = 0;
                        rhythmPhase = 0L;
                    }
                } else {
                    challengerBeatSamples = 0L;
                    challengerCount = 0;
                    tempoCandidates[tempoCandidatePosition] = best;
                    tempoCandidatePosition = (tempoCandidatePosition + 1) % tempoCandidates.length;
                    if (tempoCandidateCount < tempoCandidates.length) tempoCandidateCount++;

                    for (int i = 0; i < tempoCandidateCount; i++) tempoScratch[i] = tempoCandidates[i];
                    // Insertion sort on fixed buffers: no allocations in the audio thread.
                    for (int i = 1; i < tempoCandidateCount; i++) {
                        long value = tempoScratch[i];
                        int j = i - 1;
                        while (j >= 0 && tempoScratch[j] > value) {
                            tempoScratch[j + 1] = tempoScratch[j];
                            j--;
                        }
                        tempoScratch[j + 1] = value;
                    }
                    long median = tempoScratch[tempoCandidateCount / 2];
                    beatSamples += (long) ((median - beatSamples) * 0.08f);
                }
            }
        }
        lastOnsetFrame = frameCounter;
        rhythmPhase = 0L;
    }

    private float processBand(int band, float input, float gain) {
        if (isolateMode < 0) return input;
        float value = input * gain;
        if (isolateMode == ISOLATE_CUT_ADD) {
            if (gain < 1f) value = input * gain;
            else if (gain > 1.0001f) value += repeatL[band][repeatReadPosition()] * Math.min(0.22f, (gain - 1f) * 0.80f);
            repeatL[band][repeatPosition] = input + repeatL[band][repeatReadPosition()] * 0.22f;
        } else if (isolateMode == ISOLATE_TRANS_ROLL) {
            if (gain < 0.999f) {
                // Half-beat gate follows the adaptive low-frequency beat tracker.
                long cycle = Math.max(8000L, beatSamples / 2L);
                long phase = rhythmPhase % cycle;
                float cutAmount = Math.min(1f, 1f - gain);
                float openFrames = cycle * (0.88f - cutAmount * 0.38f);
                value *= phase < openFrames ? 1f : 0f;
            } else if (gain > 1.0001f) {
                value += repeatL[band][repeatReadPosition()] * Math.min(0.24f, (gain - 1f) * 0.90f);
                repeatL[band][repeatPosition] = input + repeatL[band][repeatReadPosition()] * 0.35f;
            }
        } else if (isolateMode == ISOLATE_GATE_DRIVE) {
            if (gain < 0.999f) {
                envelopesL[band] += (Math.abs(input) - envelopesL[band]) * 0.025f;
                float threshold = 0.012f + (1f - gain) * 0.22f;
                if (envelopesL[band] < threshold) value = 0f;
            } else if (gain > 1.0001f) {
                float drive = 1f + (gain - 1f) * 8f;
                value = softClip(value * drive) / softClip(drive);
            }
        }
        return value;
    }

    private float processBandRight(int band, float input, float gain) {
        if (isolateMode < 0) return input;
        float value = input * gain;
        if (isolateMode == ISOLATE_CUT_ADD) {
            if (gain > 1.0001f) value += repeatR[band][repeatReadPosition()] * Math.min(0.65f, (gain - 1f) * 2.2f);
            repeatR[band][repeatPosition] = input + repeatR[band][repeatReadPosition()] * 0.22f;
        } else if (isolateMode == ISOLATE_TRANS_ROLL) {
            if (gain < 0.999f) {
                long cycle = Math.max(8000L, beatSamples / 2L);
                long phase = rhythmPhase % cycle;
                float cutAmount = Math.min(1f, 1f - gain);
                float openFrames = cycle * (0.88f - cutAmount * 0.38f);
                value *= phase < openFrames ? 1f : 0f;
            } else if (gain > 1.0001f) {
                value += repeatR[band][repeatReadPosition()] * Math.min(0.8f, (gain - 1f) * 3.0f);
                repeatR[band][repeatPosition] = input + repeatR[band][repeatReadPosition()] * 0.35f;
            }
        } else if (isolateMode == ISOLATE_GATE_DRIVE) {
            if (gain < 0.999f) {
                float threshold = 0.012f + (1f - gain) * 0.22f;
                envelopesR[band] += (Math.abs(input) - envelopesR[band]) * 0.025f;
                if (envelopesR[band] < threshold) value = 0f;
            } else if (gain > 1.0001f) {
                float drive = 1f + (gain - 1f) * 8f;
                value = softClip(value * drive) / softClip(drive);
            }
        }
        return value;
    }


    private int repeatReadPosition() {
        int delay = (int) Math.max(2000L, Math.min(6000L, beatSamples / 8L));
        int read = repeatPosition - delay;
        if (read < 0) read += REPEAT_SAMPLES;
        return read;
    }

    // Gentle peak control after the three crossover bands are recombined.
    // It preserves normal dynamics and avoids harsh clipping on strong boosts.
    private float softKnee(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= 0.78f) return value;
        float excess = magnitude - 0.78f;
        float shaped = 0.78f + 0.17f * (excess / (excess + 0.17f));
        return Math.copySign(Math.min(0.95f, shaped), value);
    }

    private float softClip(float x) { return x / (1f + Math.abs(x)); }
    private float clamp(float value) { return Math.max(-LIMIT, Math.min(LIMIT, value)); }
}
