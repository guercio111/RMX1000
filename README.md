# RMX-1000 Audio Test 0.3

Standalone Android proof-of-concept. It does not modify FREQUALIZER.

## What this test does
- Requests Android MediaProjection consent.
- Captures other apps' `USAGE_MEDIA` playback using Android AudioPlaybackCapture.
- Routes captured stereo PCM through an original, lightweight Scene FX processor and AudioTrack output.
- Provides experimental Scene FX modes: Echo and Reverb.
- Adds three touch-operated Isolate FX controls: LOW, MID and HIGH.
- Isolate controls are neutral at the center (0 dB), cut down to -60 dB at the left end, and boost up to +6 dB at the right end.
- Three complementary crossover bands are summed back to a flat response when all knobs are centered.
- Runs in a foreground mediaProjection service and stops on projection revocation.
- Keeps Android status and navigation bars visible; Activity is landscape.

## Important limitations
- Android captures a copy; the source app's original audio may remain audible.
- Capture works only if the source app permits playback capture.
- Echo, Reverb and the three-band crossover are original approximations, not Pioneer firmware or exact RMX-1000 DSP models.
- This is an audio-engine prototype, not the finished RMX-1000 interface or complete effects set.
- Must be tested on the target SM-X230 / Android 16. Version 0.2's Echo and Reverb path was confirmed by the user; the new Isolate FX controls and processing require listening tests on the tablet.

## Build
GitHub Actions builds the debug APK on pushes to `main`.
