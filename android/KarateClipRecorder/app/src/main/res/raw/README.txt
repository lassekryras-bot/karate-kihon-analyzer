Karate audio cues split from the uploaded ElevenLabs MP3.

Recommended Android use:
- Use the WAV files in wav_res_raw/ for low-latency SoundPool playback.
- Copy them to: android/KarateClipRecorder/app/src/main/res/raw/
- Resource names are Android-safe: cue_ichi, cue_ni, cue_san, cue_shi, cue_go, cue_roku, cue_shichi, cue_hachi, cue_ku, cue_ju.

The split_points.csv file records the source start/end times.
These are automatic silence-based cuts, so listen once before committing.
