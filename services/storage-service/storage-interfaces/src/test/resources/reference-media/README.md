# Local reference-media fixtures

These synthetic fixtures contain a solid blue frame or a 440 Hz sine wave; they contain no user media.
They were generated with the installed FFmpeg CLI. Tests require `ffprobe` on PATH (or `STORAGE_FFPROBE_PATH`),
but do not require FFmpeg or access the network.

- `clip.mp4`, `clip.mov`: 640×640, 30 fps, 3 seconds, H.264/yuv420p.
- `h264-aac.mp4`, `hevc-mp3.mov`: supported H.264/AAC and H.265/MP3 tracks.
- `mpeg4.mp4`, `unsupported-audio.mp4`: unsupported MPEG-4 Part 2 and AC-3 codecs.
- `unsupported-second-video.mp4`, `unsupported-second-audio.mp4`: unsupported codec on the second track, after a valid first track.
- `tone.wav`, `tone.mp3`: 8 kHz mono sine, 3 seconds.
- `short` / `long`: 1 / 31 seconds; video remains 640×640 at 30 fps.
- `lowfps` / `highfps`: 20 / 61 fps, 640×640, 3 seconds.
- `small` / `large` / `wide`: 320×320 / 4096×2160 / 1280×360, 30 fps, 3 seconds.

Generation command patterns (substitute the listed sizes, rates, durations, and output filenames):

```sh
ffmpeg -y -v error -f lavfi -i color=c=blue:s=640x640:r=30 -t 3 -c:v libx264 -pix_fmt yuv420p clip.mp4
ffmpeg -y -v error -f lavfi -i sine=frequency=440:sample_rate=8000 -t 3 tone.wav
```

Rejection videos additionally use `-preset ultrafast`. WAV/MP3 and MP4/MOV containers are selected by suffix.
