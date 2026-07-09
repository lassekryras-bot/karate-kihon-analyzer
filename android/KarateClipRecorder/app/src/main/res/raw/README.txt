Karate audio cues split from the uploaded ElevenLabs MP3.

Recommended Android use:
- Use the WAV files in wav_res_raw/ for low-latency SoundPool playback.
- Copy them to: android/KarateClipRecorder/app/src/main/res/raw/
- Resource names are Android-safe: order_ichi, order_ni, order_san, order_shi, order_go, order_roku, order_shichi, order_hachi, order_ku, order_ju.

The split_points.csv file records the source start/end times.
These are automatic silence-based cuts, so listen once before committing.
