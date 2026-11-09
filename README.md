# RMX-1000 Audio Test 0.6.1

Standalone Android proof-of-concept. It does not modify FREQUALIZER.

## What this test does
- Requests Android MediaProjection consent and captures permitted `USAGE_MEDIA` playback.
- Routes stereo PCM through an original Scene FX test engine (Echo / Reverb) and three-band Isolate FX processor.
- Provides LOW, MID and HIGH rotary controls, neutral at center.
- Adds selectable Isolate FX modes: Isolator, Cut/Add, Trans/Roll and Gate/Drive.
- Isolator: independent three-band level control.
- Cut/Add: per-band attenuation on the negative side and a short band-specific repeat on the positive side.
- Trans/Roll: rhythmic band gating on the negative side with a progressive cut amount, and beat-adaptive repeats on the positive side.
- Adds a conservative low-band transient tracker that estimates tempo in a 60-180 BPM range, smooths updates, and re-aligns the rhythmic phase to detected bass attacks. A 120 BPM fallback is used until enough attacks are detected.
- Repeat delay follows the estimated beat at 1/8-note timing. Starting a new capture resets the tracker and repeat memory.
- Displays a mode-specific hint explaining the left/center/right control behavior.
- Gate/Drive: per-band threshold gating on the negative side and soft saturation on the positive side.
- Keeps the audio capture / AudioTrack engine and foreground MediaProjection service from the previous working versions.

## Important limitations
- Android captures a copy; the source app's original audio may remain audible.
- Capture works only if the source app permits playback capture.
- All DSP effects are original approximations, not Pioneer firmware, proprietary code or exact factory DSP curves.
- Beat tracking is experimental and based on low-frequency transients, not full musical beat analysis. It can misread sparse kicks, syncopation, or strong bass notes; it is not guaranteed to lock to every track. Tempo is bounded to 60-180 BPM and falls back to 120 BPM.
- This is an audio-engine prototype, not the finished RMX-1000 interface or complete effects set.
- Version 0.3 capture and three-band Isolator were confirmed by the user on a Samsung SM-X230 / Android 16. The four Isolate FX modes in 0.4 were tested by the user. Version 0.5 was confirmed by the user. Version 0.6.1 adds experimental adaptive tempo tracking, beat-adaptive repeat timing, and a corrected delay buffer for slower tempos; it requires listening tests.

## Build
GitHub Actions builds the debug APK on pushes to `main`.
