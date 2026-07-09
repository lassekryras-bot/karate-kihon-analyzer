Karate audio cues split from the uploaded ElevenLabs MP3.

Recommended Android use:
- Use the WAV files in wav_res_raw/ for low-latency SoundPool playback.
- Copy them to: android/KarateClipRecorder/app/src/main/res/raw/
- Resource names are Android-safe and ordered: cue_01_ichi, cue_02_ni, cue_03_san, cue_04_shi, cue_05_go, cue_06_roku, cue_07_shichi, cue_08_hachi, cue_09_ku, cue_10_ju.

The split_points.csv file records the source start/end times.
These are automatic silence-based cuts, so listen once before committing.
