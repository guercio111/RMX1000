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
    // Dedicated dual-head granular delay for the SPIRAL pitch sweep.
    private static final int SPIRAL_GRAIN = 4096;
    private final float[] spiralBuffer = new float[48000 * 2 * 2];
    private int spiralPosition;

    private final int[] lengths = {1493, 1601, 1747, 1867};
    private final float[][] reverbLines = new float[4][];
    private final int[] reverbPositions = new int[4];
    // Two short all-pass diffusers turn the comb network into a denser room tail.
    private final float[] diffuser1L = new float[347], diffuser1R = new float[347];
    private final float[] diffuser2L = new float[421], diffuser2R = new float[421];
    private int diffuser1Position, diffuser2Position;
    private final float[] reverbDampL = new float[4], reverbDampR = new float[4];
    private float sceneLpL, sceneLpR, sceneHpL, sceneHpR;
    private float noiseColorL, noiseColorR, crushHoldL, crushHoldR;
    private int crushCounter;
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
    // X-PAD ROLL capture/playback ring: up to two beats at 60 BPM.
    private final float[] xpadHistory = new float[48000 * 2 * 2];
    private final float[] xpadLoop = new float[48000 * 2 * 2];
    private int xpadHistoryPosition, xpadLoopFrames, xpadLoopPosition;
    private volatile boolean xpadRoll, xpadHold, xpadTouchDown;
    private volatile int xpadDivisionIndex = 2;
    private boolean xpadWasTouchDown;
    private volatile boolean xpadActive;
    private int xpadActiveDivision = -1;
    // X-PAD sample bank: 0 KICK, 1 SNARE, 2 CLAP, 3 HI HAT.
    private volatile int xpadBank = 0;
    private int xpadSampleAge = -1;
    private long xpadNextTriggerFrame;
    private float xpadKickPhase;


    public DspProcessor() {
        for (int i = 0; i < lengths.length; i++) reverbLines[i] = new float[lengths[i] * 2];
    }

    public void setMode(int value) {
        if (value < -1 || value > MODE_REVERB_DOWN || mode == value) return;
        int previous = mode;
        mode = value;
        // Do not carry a previous Scene FX tail into a different algorithm.
        // This avoids stacking unrelated delay states when the user changes FX.
        java.util.Arrays.fill(echo, 0f);
        echoPosition = 0;
        if (previous == MODE_SPIRAL_UP || previous == MODE_SPIRAL_DOWN ||
                value == MODE_SPIRAL_UP || value == MODE_SPIRAL_DOWN) {
            java.util.Arrays.fill(spiralBuffer, 0f);
            spiralPosition = 0;
        }
        sceneLpL = sceneLpR = sceneHpL = sceneHpR = 0f;
        crushHoldL = crushHoldR = 0f;
        crushCounter = 0;
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
    public void setXpadRoll(boolean value) { xpadRoll = value; if (!value) xpadActive = false; }
    public void setXpadHold(boolean value) { xpadHold = value; if (!value && !xpadTouchDown) xpadActive = false; }
    public void setXpadTouch(boolean down, int divisionIndex) {
        xpadDivisionIndex = Math.max(0, Math.min(4, divisionIndex));
        xpadTouchDown = down;
    }
    public void setXpadBank(int bank) { xpadBank = Math.max(0, Math.min(3, bank)); }
    private void triggerXpadSample() {
        xpadSampleAge = 0;
        xpadKickPhase = 0f;
    }
    private float renderXpadSample() {
        if (xpadSampleAge < 0) return 0f;
        int age = xpadSampleAge++;
        float t = age / 48000f;
        float value = 0f;
        if (xpadBank == 0) { // KICK: short falling-pitch sine with a firm transient.
            if (age < 11500) {
                float frequency = 42f + 125f * (float)Math.exp(-t * 34f);
                xpadKickPhase += (float)(2.0 * Math.PI) * frequency / 48000f;
                float env = (float)Math.exp(-t * 10.5f);
                float click = age < 420 ? (1f - age / 420f) * 0.20f : 0f;
                value = ((float)Math.sin(xpadKickPhase) * env * 0.82f) + click;
            }
        } else if (xpadBank == 1) { // SNARE: filtered noise plus a light tonal body.
            if (age < 9000) {
                noiseState = noiseState * 1664525 + 1013904223;
                float noise = ((noiseState >>> 8) / 8388608f) - 1f;
                float env = (float)Math.exp(-t * 17f);
                value = noise * env * 0.48f + (float)Math.sin(2.0 * Math.PI * 185f * t) * env * 0.16f;
            }
        } else if (xpadBank == 2) { // CLAP: three compact noise bursts.
            if (age < 10500) {
                noiseState = noiseState * 1664525 + 1013904223;
                float noise = ((noiseState >>> 8) / 8388608f) - 1f;
                float burst = (float)Math.exp(-Math.abs(t - 0.006f) * 420f)
                        + 0.72f * (float)Math.exp(-Math.abs(t - 0.021f) * 300f)
                        + 0.48f * (float)Math.exp(-Math.abs(t - 0.037f) * 260f);
                value = noise * burst * (float)Math.exp(-t * 5f) * 0.30f;
            }
        } else { // HI HAT: bright, short metallic noise.
            if (age < 3600) {
                noiseState = noiseState * 1664525 + 1013904223;
                float noise = ((noiseState >>> 8) / 8388608f) - 1f;
                float env = (float)Math.exp(-t * 65f);
                value = noise * env * 0.24f;
            }
        }
        if (age > 12000) xpadSampleAge = -1;
        return value;
    }
    private void captureXpadLoop(int divisionIndex) {
        final float[] beats = {0.125f, 0.25f, 0.5f, 1f, 2f};
        int frames = (int)Math.round(beatSamples * beats[divisionIndex]);
        frames = Math.max(32, Math.min(xpadLoop.length / 2, frames));
        int start = xpadHistoryPosition - frames * 2;
        while (start < 0) start += xpadHistory.length;
        for (int frame = 0; frame < frames; frame++) {
            int src = start + frame * 2;
            if (src >= xpadHistory.length) src -= xpadHistory.length;
            xpadLoop[frame * 2] = xpadHistory[src];
            xpadLoop[frame * 2 + 1] = xpadHistory[src + 1];
        }
        xpadLoopFrames = frames; xpadLoopPosition = 0;
        xpadActiveDivision = divisionIndex; xpadActive = true;
    }


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
        xpadHistoryPosition = xpadLoopPosition = xpadLoopFrames = 0;
        xpadWasTouchDown = xpadActive = false; xpadActiveDivision = -1;
        java.util.Arrays.fill(xpadHistory, 0f);
        java.util.Arrays.fill(xpadLoop, 0f);

        for (int band = 0; band < 3; band++) {
            java.util.Arrays.fill(repeatL[band], 0f);
            java.util.Arrays.fill(repeatR[band], 0f);
        }
    }

    /** band: 0=LOW, 1=MID, 2=HIGH; setting -1..+1, center is neutral. */
    public void setIsolateBand(int band, float setting) {
        float v = Math.max(-1f, Math.min(1f, setting));
        if (band < 0 || band > 2) return;
        float gain = bandGain(v, band);
        if (band == 0) lowTargetGain = gain;
        else if (band == 1) midTargetGain = gain;
        else highTargetGain = gain;
    }

    private float bandGain(float setting, int band) {
        // Preserve strong cuts, but keep LOW/MID boosts gentler to avoid
        // exciting bass and low-mid resonances on tablet speakers/headphones.
        if (setting < 0f) return (float)Math.pow(10.0, (setting * 48.0) / 20.0);
        // The positive half of the travel is deliberately progressive:
        // the first half stays close to unity and the last half adds only a
        // small, controlled lift. This avoids runaway energy with layered FX.
        float maxBoostDb = band == 0 ? 1.0f : (band == 1 ? 1.2f : 2.0f);
        float shaped = setting * setting;
        return (float)Math.pow(10.0, (shaped * maxBoostDb) / 20.0);
    }

    public void process(short[] pcm, int samples) {
        int usable = samples - (samples % 2);
        for (int i = 0; i < usable; i += 2) {
            float left = pcm[i] / 32768.0f;
            float right = pcm[i + 1] / 32768.0f;
            float outL = left, outR = right;

            xpadHistory[xpadHistoryPosition] = left;
            xpadHistory[xpadHistoryPosition + 1] = right;
            xpadHistoryPosition += 2;
            if (xpadHistoryPosition >= xpadHistory.length) xpadHistoryPosition = 0;
            if (xpadRoll && xpadTouchDown &&
                    (!xpadActive || !xpadWasTouchDown || xpadActiveDivision != xpadDivisionIndex)) {
                captureXpadLoop(xpadDivisionIndex);
            }
            if (xpadWasTouchDown && !xpadTouchDown && !xpadHold) xpadActive = false;
            if (xpadTouchDown && (!xpadWasTouchDown || frameCounter >= xpadNextTriggerFrame)) {
                triggerXpadSample();
                long[] beatDivisors = {8L, 4L, 2L, 1L, 1L};
                long interval = Math.max(1L, beatSamples / beatDivisors[xpadDivisionIndex]);
                if (xpadDivisionIndex == 4) interval = beatSamples * 2L;
                xpadNextTriggerFrame = frameCounter + interval;
            }
            xpadWasTouchDown = xpadTouchDown;


            if (enabled && mode >= 0) {
                float inputL = left, inputR = right;
                float wetL = 0f, wetR = 0f;

                // RMX-1000 DEFAULT-style mapping: each control has a mode-specific role.
                // Echo feedback is fixed and decays naturally; SUB 2 is never a feedback knob.
                if (mode == MODE_REVERB || mode == MODE_REVERB_DOWN) {
                    float sumL = 0f, sumR = 0f;
                    float decay = 0.38f + fxSub1 * 0.42f; // bounded decay headroom
                    float damping = 0.035f + fxSub2 * 0.24f;
                    for (int line = 0; line < reverbLines.length; line++) {
                        int p = reverbPositions[line];
                        float a = reverbLines[line][p], b = reverbLines[line][p + 1];
                        sumL += a; sumR += b;
                        reverbDampL[line] += (a - reverbDampL[line]) * damping;
                        reverbDampR[line] += (b - reverbDampR[line]) * damping;
                        float crossL = (line & 1) == 0 ? right : left;
                        float crossR = (line & 1) == 0 ? left : right;
                        float feedback = mode == MODE_REVERB_DOWN ? decay * 0.90f : decay;
                        reverbLines[line][p] = clamp(left * 0.20f + reverbDampL[line] * feedback + crossL * 0.025f);
                        reverbLines[line][p + 1] = clamp(right * 0.20f + reverbDampR[line] * feedback + crossR * 0.025f);
                        p += 2; if (p >= reverbLines[line].length) p = 0;
                        reverbPositions[line] = p;
                    }
                    wetL = sumL * 0.25f; wetR = sumR * 0.25f;
                    float d1L = diffuser1L[diffuser1Position], d1R = diffuser1R[diffuser1Position];
                    float ap1L = -0.62f * wetL + d1L, ap1R = -0.62f * wetR + d1R;
                    diffuser1L[diffuser1Position] = wetL + 0.62f * ap1L;
                    diffuser1R[diffuser1Position] = wetR + 0.62f * ap1R;
                    diffuser1Position++; if (diffuser1Position >= diffuser1L.length) diffuser1Position = 0;
                    float d2L = diffuser2L[diffuser2Position], d2R = diffuser2R[diffuser2Position];
                    float ap2L = -0.58f * ap1L + d2L, ap2R = -0.58f * ap1R + d2R;
                    diffuser2L[diffuser2Position] = ap1L + 0.58f * ap2L;
                    diffuser2R[diffuser2Position] = ap1R + 0.58f * ap2R;
                    diffuser2Position++; if (diffuser2Position >= diffuser2L.length) diffuser2Position = 0;

                    // SUB 2: high-pass tone for UP, low-pass tone for DOWN.
                    if (mode == MODE_REVERB) {
                        float a = 0.004f + fxSub2 * 0.22f;
                        sceneLpL += (ap2L - sceneLpL) * a;
                        sceneLpR += (ap2R - sceneLpR) * a;
                        wetL = ap2L - sceneLpL; wetR = ap2R - sceneLpR;
                    } else {
                        float a = 0.004f + fxSub2 * 0.16f;
                        sceneLpL += (ap2L - sceneLpL) * a;
                        sceneLpR += (ap2R - sceneLpR) * a;
                        wetL = sceneLpL; wetR = sceneLpR;
                    }
                    float amount = fxLevel * (0.25f + fxSub1 * 0.55f);
                    outL = left + wetL * amount * 1.20f;
                    outR = right + wetR * amount * 1.20f;
                } else {
                    // SUB 1 controls the beat-synchronised echo repeat interval.
                    // SCENE FX independently controls the spiral's granular delay time.
                    float delayFraction = 0.125f + fxSub1 * 0.875f;
                    if (mode == MODE_NOISE) delayFraction = 0.25f; // NOISE SUB 1 is volume, not delay.
                    int delayFrames = (int)(beatSamples * delayFraction);
                    delayFrames = Math.max(1500, Math.min(echo.length / 2 - 1, delayFrames));

                    if (mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN) {
                        // Pitch-shift the source with two overlapping read heads.
                        // Their delay ramps are opposite in direction for UP and DOWN;
                        // Hann crossfades hide grain resets without changing the beat clock.
                        spiralBuffer[spiralPosition] = left;
                        spiralBuffer[spiralPosition + 1] = right;
                        float baseDelay = beatSamples * (0.125f + fxLevel * 0.875f);
                        float amount = fxSub2;
                        float phaseA = (frameCounter % SPIRAL_GRAIN) / (float)SPIRAL_GRAIN;
                        float phaseB = phaseA < 0.5f ? phaseA + 0.5f : phaseA - 0.5f;
                        float rate = mode == MODE_SPIRAL_UP ? 1.0f : 0.5f;
                        float swing = amount * rate * SPIRAL_GRAIN;
                        float delayA = mode == MODE_SPIRAL_UP
                                ? baseDelay + swing * (0.5f - phaseA)
                                : baseDelay + swing * (phaseA - 0.5f);
                        float delayB = mode == MODE_SPIRAL_UP
                                ? baseDelay + swing * (0.5f - phaseB)
                                : baseDelay + swing * (phaseB - 0.5f);
                        float weightA = 0.5f - 0.5f * (float)Math.cos(phaseA * Math.PI * 2.0);
                        float weightB = 1.0f - weightA;
                        float readA = spiralPosition - delayA * 2f;
                        float readB = spiralPosition - delayB * 2f;
                        inputL = readSpiral(spiralBuffer, readA) * weightA
                                + readSpiral(spiralBuffer, readB) * weightB;
                        inputR = readSpiral(spiralBuffer, readA + 1f) * weightA
                                + readSpiral(spiralBuffer, readB + 1f) * weightB;
                        spiralPosition += 2;
                        if (spiralPosition >= spiralBuffer.length) spiralPosition = 0;
                    }

                    if (mode == MODE_NOISE) {
                        // White noise -> band-pass -> beat-synchronised echo.
                        noiseState = noiseState * 1664525 + 1013904223;
                        float nL = ((noiseState >>> 8) / 8388608f) - 1f;
                        noiseState = noiseState * 1664525 + 1013904223;
                        float nR = ((noiseState >>> 8) / 8388608f) - 1f;
                        float lp = 0.012f + fxLevel * 0.34f;
                        sceneLpL += (nL - sceneLpL) * lp; sceneLpR += (nR - sceneLpR) * lp;
                        float hp = 0.012f + fxLevel * 0.12f;
                        sceneHpL += (sceneLpL - sceneHpL) * hp; sceneHpR += (sceneLpR - sceneHpR) * hp;
                        inputL = (sceneLpL - sceneHpL) * fxSub1 * 0.72f;
                        inputR = (sceneLpR - sceneHpR) * fxSub1 * 0.72f;
                    } else if (mode == MODE_BPF_ECHO) {
                        // SCENE FX sweeps the band-pass cutoff; SUB 2 modulates the output.
                        float lp = 0.012f + fxLevel * 0.34f;
                        sceneLpL += (inputL - sceneLpL) * lp; sceneLpR += (inputR - sceneLpR) * lp;
                        float hp = 0.006f + fxLevel * 0.11f;
                        sceneHpL += (sceneLpL - sceneHpL) * hp; sceneHpR += (sceneLpR - sceneHpR) * hp;
                        inputL = (sceneLpL - sceneHpL) * 1.35f; inputR = (sceneLpR - sceneHpR) * 1.35f;
                    } else if (mode == MODE_HPF_ECHO) {
                        // SCENE FX = high-pass cutoff.
                        float a = 0.004f + fxLevel * 0.22f;
                        sceneLpL += (inputL - sceneLpL) * a; sceneLpR += (inputR - sceneLpR) * a;
                        inputL -= sceneLpL; inputR -= sceneLpR;
                    } else if (mode == MODE_LPF_ECHO) {
                        // SCENE FX = low-pass cutoff.
                        float a = 0.004f + fxLevel * 0.28f;
                        sceneLpL += (inputL - sceneLpL) * a; sceneLpR += (inputR - sceneLpR) * a;
                        inputL = sceneLpL; inputR = sceneLpR;
                    } else if (mode == MODE_CRUSH_ECHO) {
                        // SCENE FX controls the degree of bit/sample crushing.
                        int bits = 16 - Math.round(fxLevel * 10f);
                        float steps = (float)(1 << bits);
                        int holdEvery = 1 + Math.round(fxLevel * 11f);
                        if (crushCounter % holdEvery == 0) {
                            crushHoldL = Math.round(inputL * steps) / steps;
                            crushHoldR = Math.round(inputR * steps) / steps;
                        }
                        inputL = crushHoldL; inputR = crushHoldR; crushCounter++;
                    }

                    // SUB 2 is modulation on BPF/HPF/LPF/CRUSH/NOISE echoes.
                    int modulation = 0;
                    if (mode == MODE_BPF_ECHO || mode == MODE_HPF_ECHO || mode == MODE_LPF_ECHO ||
                            mode == MODE_CRUSH_ECHO || mode == MODE_NOISE) {
                        modulation = (int)(Math.sin(frameCounter * 0.0017f) * fxSub2 * 180f);
                    }

                    int read = echoPosition - delayFrames * 2 - modulation * 2;
                    while (read < 0) read += echo.length;
                    while (read >= echo.length) read -= echo.length;
                    float delayedL = echo[read], delayedR = echo[read + 1];

                    // Musical feedback is fixed, so repeats attenuate instead of self-oscillating.
                    float feedback = 0.38f;
                    if (mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN) feedback = 0.20f;
                    else if (mode == MODE_HPF_ECHO) feedback = 0.18f;
                    else if (mode == MODE_CRUSH_ECHO) feedback = 0.16f;
                    else if (mode == MODE_NOISE) feedback = 0.30f;
                    echo[echoPosition] = clamp(inputL + delayedL * feedback);
                    echo[echoPosition + 1] = clamp(inputR + delayedR * feedback);

                    if (mode == MODE_ECHO) {
                        // SCENE FX = dry/wet balance; SUB 2 = high-pass cutoff of repeats.
                        float a = 0.004f + fxSub2 * 0.20f;
                        sceneLpL += (delayedL - sceneLpL) * a; sceneLpR += (delayedR - sceneLpR) * a;
                        float hpL = delayedL - sceneLpL, hpR = delayedR - sceneLpR;
                        float mix = fxLevel;
                        outL = left * (1f - mix * 0.85f) + left * 0.15f + hpL * mix * 1.25f;
                        outR = right * (1f - mix * 0.85f) + right * 0.15f + hpR * mix * 1.25f;
                    } else {
                        float wet = 0.68f;
                        if (mode == MODE_SPIRAL_UP || mode == MODE_SPIRAL_DOWN) wet = 0.46f;
                        else if (mode == MODE_HPF_ECHO) wet = 0.44f;
                        else if (mode == MODE_CRUSH_ECHO) wet = 0.42f;
                        else if (mode == MODE_NOISE) wet = 0.65f;
                        outL = left + delayedL * wet;
                        outR = right + delayedR * wet;
                    }
                    if (mode == MODE_CRUSH_ECHO) {
                        // Keep the crushed character without adding a second large gain stage.
                        outL = softClip(outL * (1.0f + fxLevel * 0.25f));
                        outR = softClip(outR * (1.0f + fxLevel * 0.25f));
                    }
                    echoPosition += 2; if (echoPosition >= echo.length) echoPosition = 0;
                }
            }

            if (xpadRoll && xpadActive && xpadLoopFrames > 0) {
                int xp = xpadLoopPosition * 2;
                outL = clamp(outL + xpadLoop[xp] * 0.72f);
                outR = clamp(outR + xpadLoop[xp + 1] * 0.72f);
                xpadLoopPosition++;
                if (xpadLoopPosition >= xpadLoopFrames) xpadLoopPosition = 0;
            }
            // Percussion samples are mixed over the captured music, not substituted for it.
            float padSample = renderXpadSample();
            outL = clamp(outL + padSample);
            outR = clamp(outR + padSample * 0.985f);

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
            else if (gain > 1.0001f) value += repeatL[band][repeatReadPosition()] * Math.min(0.055f, (gain - 1f) * 0.22f);
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
                value += repeatL[band][repeatReadPosition()] * Math.min(0.060f, (gain - 1f) * 0.24f);
                repeatL[band][repeatPosition] = input + repeatL[band][repeatReadPosition()] * 0.35f;
            }
        } else if (isolateMode == ISOLATE_GATE_DRIVE) {
            if (gain < 0.999f) {
                envelopesL[band] += (Math.abs(input) - envelopesL[band]) * 0.025f;
                float threshold = 0.012f + (1f - gain) * 0.22f;
                if (envelopesL[band] < threshold) value = 0f;
            } else if (gain > 1.0001f) {
                float drive = 1f + Math.min(1.2f, (gain - 1f) * 4f);
                // Drive adds harmonic density but must not act as a volume boost.
                value = softClip(value * drive) / drive;
            }
        }
        return value;
    }

    private float processBandRight(int band, float input, float gain) {
        if (isolateMode < 0) return input;
        float value = input * gain;
        if (isolateMode == ISOLATE_CUT_ADD) {
            if (gain > 1.0001f) value += repeatR[band][repeatReadPosition()] * Math.min(0.22f, (gain - 1f) * 0.80f);
            repeatR[band][repeatPosition] = input + repeatR[band][repeatReadPosition()] * 0.22f;
        } else if (isolateMode == ISOLATE_TRANS_ROLL) {
            if (gain < 0.999f) {
                long cycle = Math.max(8000L, beatSamples / 2L);
                long phase = rhythmPhase % cycle;
                float cutAmount = Math.min(1f, 1f - gain);
                float openFrames = cycle * (0.88f - cutAmount * 0.38f);
                value *= phase < openFrames ? 1f : 0f;
            } else if (gain > 1.0001f) {
                value += repeatR[band][repeatReadPosition()] * Math.min(0.24f, (gain - 1f) * 0.90f);
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


    /** Linear-interpolated read from the interleaved stereo spiral delay ring. */
    private float readSpiral(float[] buffer, float position) {
        float length = buffer.length;
        while (position < 0f) position += length;
        while (position >= length) position -= length;
        int index = (int)position;
        int next = index + 2;
        if (next >= buffer.length) next = 0;
        float fraction = position - index;
        return buffer[index] + (buffer[next] - buffer[index]) * fraction;
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
