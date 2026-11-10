package it.rmx1000.test;

/**
 * Streaming beat estimator for 48 kHz PCM.
 *
 * Audio analysis and clock generation are deliberately separate:
 * this class estimates tempo/phase from onset novelty; CaptureService owns
 * the monotonic clock and never derives clock timing from noisy BPM updates.
 */
public final class BeatEngine {
    public static final int SAMPLE_RATE = 48000;
    private static final int HOP = 256;
    private static final int RING_SIZE = 2400; // 12.8 s at 5.33 ms/hop
    private final float[] novelty = new float[RING_SIZE];
    private final float[] previousBands = new float[3];
    private final float[] lowState = new float[3];
    private int ringWrite, ringCount, samplesUntilHop;
    private float frameEnergy;
    private float detectedBpm = 0f;
    private float confidence = 0f;
    private long lastAnalysisMs;
    private long lastOnsetMs;
    private long phaseMs;
    private double periodMs;
    private float manualOffset;
    private boolean locked;

    public synchronized void reset() {
        ringWrite = ringCount = samplesUntilHop = 0;
        frameEnergy = 0f;
        detectedBpm = confidence = 0f;
        lastAnalysisMs = lastOnsetMs = phaseMs = 0L;
        periodMs = 0.0;
        locked = false;
        for (int i = 0; i < 3; i++) { previousBands[i] = 0f; lowState[i] = 0f; }
    }

    public synchronized void setManualOffset(float bpm) {
        manualOffset = Math.max(-10f, Math.min(10f, bpm));
    }

    public synchronized float getManualOffset() { return manualOffset; }
    public synchronized float getDetectedBpm() { return detectedBpm; }
    public synchronized float getConfidence() { return confidence; }
    public synchronized float getClockBpm() {
        if (detectedBpm <= 0f) return 0f;
        return Math.max(50f, Math.min(250f, detectedBpm + manualOffset));
    }
    public synchronized boolean isLocked() { return locked; }
    public synchronized long getPhaseMs() { return phaseMs; }
    public synchronized double getPeriodMs() { return periodMs; }

    /** Request a phase re-anchor. The service calls this on the user's SYNC action. */
    public synchronized void sync(long nowMs) {
        if (periodMs <= 0.0) return;
        phaseMs = nowMs;
        locked = true;
    }

    public synchronized void process(short[] pcm, int length, long nowMs) {
        if (pcm == null) return;
        int usable = Math.min(length, pcm.length);
        for (int i = 0; i + 1 < usable; i += 2) {
            float x = ((pcm[i] + pcm[i + 1]) * 0.5f) / 32768f;
            // Three inexpensive low-pass bands; novelty is weighted toward kick/snare,
            // but retains mid/high transient information for non-kick-led material.
            lowState[0] += 0.035f * (x - lowState[0]);
            lowState[1] += 0.18f * (x - lowState[1]);
            lowState[2] += 0.55f * (x - lowState[2]);
            frameEnergy += x * x;
            samplesUntilHop++;
            if (samplesUntilHop >= HOP) {
                float low = Math.abs(lowState[0]);
                float mid = Math.abs(lowState[1] - lowState[0]);
                float high = Math.abs(x - lowState[2]);
                float flux = 0f;
                float riseLow = low - previousBands[0];
                float riseMid = mid - previousBands[1];
                float riseHigh = high - previousBands[2];
                if (riseLow > 0f) flux += riseLow;
                if (riseMid > 0f) flux += riseMid * 1.25f;
                if (riseHigh > 0f) flux += riseHigh * 1.25f;
                previousBands[0] = low * 0.88f + previousBands[0] * 0.12f;
                previousBands[1] = mid * 0.88f + previousBands[1] * 0.12f;
                previousBands[2] = high * 0.88f + previousBands[2] * 0.12f;
                float rms = (float)Math.sqrt(frameEnergy / HOP);
                float onset = flux * Math.min(1f, rms * 7f);
                novelty[ringWrite] = onset;
                ringWrite = (ringWrite + 1) % RING_SIZE;
                if (ringCount < RING_SIZE) ringCount++;
                frameEnergy = 0f;
                samplesUntilHop = 0;
            }
        }
        if (nowMs - lastAnalysisMs >= 250L && ringCount >= 700) {
            analyse(nowMs);
            lastAnalysisMs = nowMs;
        }
    }

    private void analyse(long nowMs) {
        // Autocorrelation over onset-strength history. Search 60..200 BPM.
        int minLag = (int)(60000.0 / 200.0 / (1000.0 * HOP / SAMPLE_RATE));
        int maxLag = (int)(60000.0 / 60.0 / (1000.0 * HOP / SAMPLE_RATE));
        int window = Math.min(ringCount - maxLag - 1, 1100);
        if (window < 500) return;
        double mean = 0.0;
        for (int i = 0; i < window; i++) mean += atFromNewest(i);
        mean /= window;
        double best = -1.0;
        int bestLag = 0;
        double[] scores = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double xy = 0.0, xx = 0.0, yy = 0.0;
            for (int i = 0; i < window; i++) {
                double a = atFromNewest(i) - mean;
                double b = atFromNewest(i + lag) - mean;
                xy += a * b; xx += a * a; yy += b * b;
            }
            double score = xy / Math.sqrt(Math.max(1e-12, xx * yy));
            scores[lag] = score;
            if (score > best) { best = score; bestLag = lag; }
        }
        if (bestLag == 0 || best < 0.12) {
            confidence *= 0.92f;
            if (confidence < 0.12f) locked = false;
            return;
        }
        // Prefer a nearby harmonic only when it has comparable evidence.
        int chosen = bestLag;
        int half = bestLag / 2;
        int twice = bestLag * 2;
        if (half >= minLag && scores[half] > best * 0.92) chosen = half;
        else if (twice <= maxLag && scores[twice] > best * 0.94) chosen = twice;
        float raw = (float)(60000.0 / (chosen * (1000.0 * HOP / SAMPLE_RATE)));
        raw = Math.max(60f, Math.min(200f, raw));
        float quality = (float)Math.max(0.0, Math.min(1.0, (best - 0.08) / 0.55));
        if (detectedBpm == 0f || confidence < 0.15f) detectedBpm = raw;
        else {
            float maxStep = 1.5f;
            float delta = Math.max(-maxStep, Math.min(maxStep, raw - detectedBpm));
            detectedBpm += delta * (0.12f + quality * 0.18f);
        }
        confidence = confidence * 0.75f + quality * 0.25f;
        double newPeriod = 60000.0 / getClockBpm();
        if (periodMs <= 0.0) {
            periodMs = newPeriod;
            phaseMs = nowMs;
            locked = confidence > 0.25f;
        } else {
            // Smooth tempo changes without allowing the phase to jump with every estimate.
            periodMs += (newPeriod - periodMs) * 0.04;
            if (!locked && confidence > 0.35f) { phaseMs = nowMs; locked = true; }
        }
    }

    private float atFromNewest(int age) {
        int index = ringWrite - 1 - age;
        while (index < 0) index += RING_SIZE;
        return novelty[index];
    }
}
