# RMX-1000 Audio Test 0.5

Standalone Android proof-of-concept. It does not modify FREQUALIZER.

## What this test does
- Requests Android MediaProjection consent and captures permitted `USAGE_MEDIA` playback.
- Routes stereo PCM through an original Scene FX test engine (Echo / Reverb) and three-band Isolate FX processor.
- Provides LOW, MID and HIGH rotary controls, neutral at center.
- Adds selectable Isolate FX modes: Isolator, Cut/Add, Trans/Roll and Gate/Drive.
- Isolator: independent three-band level control.
- Cut/Add: per-band attenuation on the negative side and a short band-specific repeat on the positive side.
- Trans/Roll: rhythmic band gating on the negative side with a progressive cut amount, and short repeats on the positive side.
- Resets the Trans/Roll rhythmic phase and repeat memory at the beginning of each new capture, so the pattern starts from a predictable point.
- Displays a mode-specific hint explaining the left/center/right control behavior.
- Gate/Drive: per-band threshold gating on the negative side and soft saturation on the positive side.
- Keeps the audio capture / AudioTrack engine and foreground MediaProjection service from the previous working versions.

## Important limitations
- Android captures a copy; the source app's original audio may remain audible.
- Capture works only if the source app permits playback capture.
- All DSP effects are original approximations, not Pioneer firmware, proprietary code or exact factory DSP curves.
- Trans/Roll timing is currently fixed around a 120 BPM reference; automatic beat detection and editable timing are not implemented. Capture restart resets the effect phase but does not detect or lock to the music's actual BPM.
- This is an audio-engine prototype, not the finished RMX-1000 interface or complete effects set.
- Version 0.3 capture and three-band Isolator were confirmed by the user on a Samsung SM-X230 / Android 16. The four Isolate FX modes in 0.4 were tested by the user. Version 0.5 refines Trans/Roll and still requires listening tests.

## Build
GitHub Actions builds the debug APK on pushes to `main`.
