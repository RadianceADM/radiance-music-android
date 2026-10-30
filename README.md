# Radiance Music for Android

The phone side of [Radiance Music](https://github.com/RadianceADM/radiance-music): search any
song and play it, lossless first. **Like it, save it, split it:** a liked song is saved on your
phone with its cover, its lyrics and its music video, and split into its drums, bass, other and
vocals right there.

**Get it:** [music.radianceph.com](https://music.radianceph.com), or the newest
`RadianceMusic.apk` from [Releases](https://github.com/RadianceADM/radiance-music-android/releases).
Your phone asks once to allow installing apps from your browser (that's how an app from outside
the Play Store goes on).

## What it does

- **Search any song:** lossless FLAC from the Internet Archive first, YouTube Music when it
  isn't there, with suggestions as you type and your liked songs first.
- **Like it, save it:** the song goes to `Music/Radiance Music` (Opus from YouTube, the best it
  has, or FLAC). Its official music video goes to `Movies/Radiance Music` (720p). Its cover and
  timed lyrics are kept too, so it plays offline.
- **Split it, on the phone:** any saved song splits into drums, bass, other and vocals with the
  same Demucs model the computer app uses (as ONNX, inside the app: nothing to download first,
  and the song isn't sent anywhere). It pauses when the battery is low or the phone runs hot.
- **The Mixer:** a slider for each part (No vocals and Vocals only in one tap), plus low cut,
  high cut and reverb for the whole sound.
- **Share it to your story:** a song's cover, or the lyric lines you pick, as a card made for
  Instagram and Facebook stories, or saved to your photos.
- **Send it by QR:** songs go to another phone, or between your computer and your phone, with
  their parts, lyrics and covers; what the other side already has is skipped. With no Wi-Fi
  around, the phone showing the QR makes its own (Wi-Fi Direct).
- **Plays in the background:** the notification, the lock screen, and headphone and Bluetooth
  buttons. Unplug your headphones and it pauses.
- **Now Playing:** the cover's colours flow behind it, and the lyrics show the sung line big.
  Tap a line to go there.
- **Queue, Liked Songs (A to Z, searchable) and History.**

## What it needs

| | |
|---|---|
| Phone | Android 10 or newer, 64-bit (arm64) |
| Space | about 210 MB for the app (the splitter's model is inside it), plus your songs |
| Network | for searching, streaming and saving; songs you saved play offline |

Splitting a 4-minute song takes about 3½ minutes on a Galaxy S24 Ultra; slower phones take
longer. Saving needs no storage permission (Android 10's shared folders); reading songs copied
from your computer asks for Music and audio once.

## Build

```sh
export ANDROID_HOME=/path/to/android-sdk   # platform 37, build-tools 36.1.0
gradle assembleRelease                     # Gradle 9.8+, JDK 17 or newer
```

The build fetches the splitter's model once (Demucs as ONNX, 166 MB, checked against its
SHA-256) and puts it inside the app. The release is signed with the key named in
`signing.properties` (outside this repository; the path comes from `RADIANCE_SIGNING`).
Without it, the build is signed with the debug key.

## YouTube

YouTube Music plays through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor),
which keeps up with YouTube's changes. Saving songs and videos from YouTube goes against
YouTube's terms of service; that's your choice to make, for your own use.

## Licence

GNU GPL, version 3 or later. It uses NewPipe Extractor (GPL-3.0), Media3 (Apache 2.0), OkHttp
(Apache 2.0), Coil (Apache 2.0), ONNX Runtime (MIT), the Demucs model (MIT, exported to ONNX by
StemSplitio, MIT), ZXing (Apache 2.0) and CameraX (Apache 2.0).
