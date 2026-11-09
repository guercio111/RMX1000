# RMX-1000 Audio Test 0.1

Standalone Android proof-of-concept. It does not modify FREQUALIZER.

## What this test does
- Requests Android MediaProjection consent.
- Captures other apps' `USAGE_MEDIA` playback using AudioPlaybackCapture.
- Routes captured stereo PCM through a simple 250 ms feedback echo and AudioTrack output.
- Runs in a foreground mediaProjection service and stops on projection revocation.
- Keeps Android status and navigation bars visible; Activity is landscape.

## Known limitations
- Android captures a copy; the source app's original audio may remain audible.
- Capture works only if the source app permits playback capture.
- This is a technical audio-path test, not an RMX-1000 DSP clone.
- The echo is intentionally simple and fixed at 250 ms; no BPM sync.
- Must be tested on the target SM-X230 / Android 16. No device test has been performed yet.

## Build
Push this project to a separate GitHub repository, then run the `Build RMX-1000 test APK` workflow (or let it run on push to `main`). Download the `RMX1000-Audio-Test-debug` artifact from the workflow run.
