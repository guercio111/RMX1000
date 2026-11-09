# RMX-1000 Audio Test 0.2

Standalone Android proof-of-concept. It does not modify FREQUALIZER.

## What this test does
- Requests Android MediaProjection consent.
- Captures other apps' `USAGE_MEDIA` playback using Android AudioPlaybackCapture.
- Routes captured stereo PCM through an original, lightweight Scene FX processor and AudioTrack output.
- Provides two experimental Scene FX modes: Echo and Reverb.
- Runs in a foreground mediaProjection service and stops on projection revocation.
- Keeps Android status and navigation bars visible; Activity is landscape.

## Important limitations
- Android captures a copy; the source app's original audio may remain audible.
- Capture works only if the source app permits playback capture.
- The Echo and Reverb algorithms are original approximations, not Pioneer firmware or exact RMX-1000 DSP models.
- This is still an audio-engine test, not the finished RMX-1000 interface or complete effects set.
- Must be tested on the target SM-X230 / Android 16. The first audio path was confirmed by the user; the new Reverb mode still requires listening tests on the tablet.

## Build
The GitHub Actions workflow builds the debug APK on pushes to `main` and supports manual runs from the Actions tab.
