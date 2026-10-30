# Changelog

## 0.5.7

- Story cards say where to get the app at its own site now: "Get it free at
  music.radianceph.com".

## 0.5.6

- **Songs split in six on the computer** play whole in the Mixer: their Piano and Guitar play
  too, moving with the Other slider.
- Library: "N songs, saved on this phone" has a line of its own, with Play, Shuffle and the QR
  button under it (it was squeezed into a narrow column on some phones).

## 0.5.5

- **The splitter comes with the app:** its model (Demucs, 166 MB) is inside the app now, so
  splitting a song works right away, with nothing to download first and nothing asked. The
  copy earlier versions downloaded is removed, and its space comes back. (The app is bigger
  for it, about 210 MB.)

## 0.5.4

- **The start, all of it:** the sunrise in the app's own dark (no navy), the sun setting again,
  then the logo writing itself (the R, its leg rolling on into the m, the wave fading out) with
  "Radiance Music" under it, and the dark opening from the middle out into the app (not all at
  once). As the computer app's. A tap goes straight in.

## 0.5.3

- **A sunrise when the app starts:** the icon's sun rises out of the sea and its four bars
  play like an equalizer, then it fades into the app (about two seconds; a tap goes straight
  in). Only when the app really starts, not when you come back to it, and not with
  animations turned off. Android's own splash before it is plain navy now, so the two flow
  into each other.

## 0.5.2

- **The Radiance Music logo on story cards:** the R, its leg rolling on as a wave shaped
  like an m (R + M), fading out. It stands on its own beside "Radiance Music / Get it free
  at radianceph.com/music", as tall as the two lines. The app's icon stays the sunset.

## 0.5.1

- **New look: the sunset with four parts.** A warm sun with four sound bars standing in it
  (drums, bass, other, vocals) on deep navy: the app's icon (and its themed-icon version),
  the notifications' icon, and on every story card.
- Story cards carry the name and where to get the app: "Get it free at
  radianceph.com/music".

## 0.5.0

- **Share songs by QR**, straight to another phone (or the computer), with their four
  parts, lyrics and cover (and music videos, if you like): the other side doesn't have
  to download or split anything. Library → the QR button, or hold a song → Send to a
  phone…
- Nothing the other side has already is sent again: songs it has saved, parts it has
  split, lyrics and covers it has. Only what's missing goes.
- **Without Wi-Fi too:** the phone showing the QR makes its own Wi-Fi (Wi-Fi Direct) and
  the QR tells the other phone how to join it. On the same Wi-Fi, that's used first.
- Scan with the app (Library → the QR button → Scan a QR) or the phone's own camera.
- While music plays to Bluetooth, songs move at most 3 MB a second, so the music
  doesn't stutter.

## 0.4.0

- **Share a song as a story.** Now Playing → Share (or hold a song → Share…): a story
  picture (1080 × 1920) with its cover on a soft blur of itself, the title and who sings
  it. Pick up to 5 lines of its lyrics and they go on the card, big. Then Share opens
  Android's share menu (Instagram and Facebook stories, Messenger, WhatsApp…) with the
  song's link for apps that take words, or Save to Photos (Pictures/Radiance Music).
- Hold a line in the lyrics to share it: the card starts with that line picked.

## 0.3.1

- Splitting pauses by itself when the battery is below 20 % and the phone isn't
  charging, or when the phone gets hot. It goes on once the phone is charging (or back
  to 25 %) or has cooled down. The Mixer and the notification say why it's paused.

## 0.3.0

- **Split songs on the phone itself.** Mixer → Split this song: drums, bass, other and
  vocals, with the same Demucs model as the computer, on the phone's own cores. Nothing
  goes to a server. The first split gets the splitter once (166 MB, from Hugging Face).
- Splits run in the background, one at a time, even with the screen off, with a
  notification showing how far along and how long is left. Stop or skip any of them.
- **Cores**: how many of the phone's cores the splitter uses (half by default). More is
  quicker; fewer keeps the phone cooler.
- A song that isn't saved yet is liked and saved first, then split.

## 0.2.1

- The Mixer has the effects now: **Low cut**, **High cut** and **Reverb**, made the way
  the computer's Speaker EQ makes them (the same filters, the same room). They work on
  every song, split or not, and stay set from song to song.
- The Mixer button shows for every song. The parts' sliders appear only when the song
  is split on this phone.

## 0.2.0

- **Mixer** for split songs: Drums, Bass, Other and Vocals, each as loud as you like.
  The song stays as loud as it was whole when you turn a part down. No vocals,
  Vocals only, Reset.

## 0.1.2

- Reads the library copied from the computer: songs, videos, parts, lyrics and covers
  go into Liked Songs.

## 0.1.1

- YouTube songs save as .m4a (Android wouldn't take Opus in WebM).

## 0.1.0

- First version: search (Internet Archive lossless first, then YouTube Music), plays in
  the background and on the lock screen, like a song to save it with its video, cover
  and lyrics, queue, history, Liked Songs.
