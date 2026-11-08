# Changelog

## 1.1.0

- **Made for tablets and car stereos:** on a tablet, an Android car stereo or a phone on its
  side, the tabs move to a rail down the left side, and Home, Search, Library and Settings
  keep to an easy-to-read width in the middle of the screen. When the screen is wider than
  tall, Now Playing puts the cover beside the controls; Lyrics take the cover's place, so the
  controls stay where they are. Sharing to a story shows the story beside its choices, and
  How to Use It puts each picture beside its words. On a phone held upright, nothing changes.
- **Sharp covers:** songs found on YouTube Music came with a small cover (120 pixels), blurry
  on Now Playing and on a story. Now each cover comes at the size it's shown: big on Now
  Playing, in a story and in the cover kept with a liked song, small in lists so searching
  uses little data. The covers of songs you liked before are fetched again big, by themselves.

## 1.0.5

- **No music videos on the phone:** Radiance Music on your phone is for songs. Liking a song
  saves the song, its cover and its lyrics, and it's split for karaoke, but no music video.
  Now Playing has no Video button, and songs shared from the computer or another phone come
  without videos. The music videos kept from before are deleted by the update itself, so
  their space is free again. Story videos for Instagram and Facebook are still there.

## 1.0.4

- **Karaoke says what it's waiting for:** the first tap on the mic splits the song on your
  phone, with a ring going round the mic. A second tap while the ring turns used to switch
  karaoke off without a word; now it stays on and comes in by itself when the split is done.
  When the splitter couldn't download (no internet, or the download stopped), the line under
  the mic says so, with Try Again, and while it downloads it shows how far along it is.
- **Music keeps playing smoothly while songs are split ahead:** the next song (and your liked
  songs, in the background) is now split on one core only, so the phone isn't busy enough to
  make the music skip. When that song starts playing, its split speeds up to the cores set in
  Settings.
- **Report a Problem sends the app's own log:** some phones log their screen drawing many
  times a second, which pushed every line of the app's own out of the report. Now the report
  keeps the app's lines and leaves that chatter out.

## 1.0.3

- **Every liked song is split by itself:** like a song and, once it's saved, it's split into
  its vocals and its music too, as its cover, lyrics and music video are, so karaoke is ready
  whenever you want it. Songs you liked before are split the same way, one at a time in the
  background (the song you're playing with karaoke on always goes first). Uninstalling the app
  deletes its splits, but they come back by themselves: the songs come back, then they're
  split again.

## 1.0.2

- **Music videos are out of Gallery:** a liked song's music video used to be saved in
  Movies/Radiance Music, so Gallery showed every one. Now they're kept inside the app, and the
  ones already saved move in by themselves. Old copies the app didn't make itself (copied from
  your computer, or saved before the app was installed again) need one tap: Android asks once
  before they leave Gallery. Uninstalling the app now deletes its music videos too (your songs
  in Music/Radiance Music stay).
- **Saved songs come back by themselves, every time:** songs saved in Music/Radiance Music
  that aren't in Liked Songs now come back each time the app opens, not only when Liked Songs
  is empty, with nothing for you to do. A song you unlike stays out.
- **Watch the music video in the app:** a Video button at the top right of the song's cover
  plays its music video in the cover's place, from the same spot, without stopping, as on the
  computer. Song goes back to the song, from where the video was. A video that isn't saved on
  the phone plays from YouTube.

## 1.0.1

- **Your saved songs come back:** uninstalling the app erases its list of liked songs, but the
  songs themselves stay in Music/Radiance Music. When Liked Songs is empty, the app now finds
  them and puts them back, with their music videos, covers and lyrics (it asks to see your
  music and videos first). Any time: Settings → Find songs saved on this phone.
- **Songs like the one you picked play next:** a song played from search is now followed by
  songs like it, as YouTube Music plays after it, instead of the other search results.
- **Splitting keeps going when the phone is warm:** it used to pause as soon as Android said
  the phone was hot, which some phones say when they're only warm. Now it goes on, slower, on
  two cores, and pauses only when the phone is really hot. Splitting uses four cores to begin
  with (Settings → Splitting for more or fewer).
- About no longer says "open beta": since 1.0.0 the phone app is stable.

## 1.0.0

The first stable release of Radiance Music for Android.

- **Karaoke in one tap:** there's a mic button beside Lyrics on the song's screen, as in Apple
  Music. Tap it and the singing goes down; tap it again for the whole song. Like Apple Music,
  a little of the singing stays so you can follow along. Press and slide the mic up or down for
  more or less, all the way out if you like. A song that isn't split yet is split first (about
  15 seconds), and karaoke comes in by itself. Karaoke stays on from song to song, and the next
  song is split while this one plays, so it starts as karaoke too.
- **Smooth switching:** a split song now plays as one stream, the song itself and its parts
  together, so turning karaoke on or off just glides the singing down or up in a third of a
  second. Nothing stops, restarts or skips, right after you jump to another part of the song
  too. The whole song is the song itself, as it is (lossless), not its parts added up.
- **No more jumps in karaoke:** karaoke no longer keeps snapping back in step every few seconds
  (each snap was a tiny cut in the sound), and the split song's sound comes first on the phone,
  so it doesn't skip while the phone is busy (splitting the next song, for one).
- **Splitting is about 20 times faster:** a song now splits in two, its vocals and its music,
  in seconds instead of minutes (about 10 to 20 seconds for a 4-minute song on a recent phone).
  It uses Deezer's Spleeter instead of Demucs. Songs split in four (on your computer, on a
  server, or on the phone before) still play as karaoke or vocals only.
- **A smaller splitter:** it downloads once at the first open, 39 MB instead of 166 MB, and the
  old one is deleted from your phone (166 MB back).
- **Splitting starts sooner and shows it:** a song waiting to be split no longer waits for its
  music video to download first, and reading the song before the split now uses all the cores
  the split uses. The bar shows the download and the reading too, and keeps moving between
  steps instead of sitting at 0%.
- **A simpler Mixer:** one tap each for Whole song, Karaoke and Vocals only, plus the effects.
  The sliders for each part (Drums, Bass, Other, Vocals) are gone, for songs split on your
  computer or a server too.
- **Pick the part of the song for your story:** under the story there's now a strip of the song.
  Drag the bright part to the part you want (it plays as you drag), and drag its ends to make it
  from 6 to 30 seconds. Before, a story without lyrics always took 15 seconds from where the song
  was playing. With timed lyric lines picked, the lines still set the part, and the strip shows it.
- **Sharing songs closes when you're done:** closing the Share songs screen now stops a QR that
  nobody is using, so it doesn't stay open in the background keeping the phone awake. Songs
  already on their way keep going, and the notification has a Stop button (tap it to see the
  share). A QR left unused closes by itself after a few minutes.
- **"Are you enjoying Radiance Music?"** Now and then (after a week and 30 songs, at most once
  a month, never mid-song) the app asks for five quick star ratings and anything you'd like to
  say. Sending signs you in with Google in one tap; your name and email are kept with your
  rating (music.radianceph.com/privacy). Not now, or Don't ask again. Any time: Settings → Rate
  Radiance Music. Four or five stars? Share Radiance Music with a friend.
- Home's "Play your mix" and "Liked, A to Z" no longer break in two: when they don't fit side by
  side (a bigger font), the second goes under the first.

## 0.5.15

- **The A to Z rail stays beside your songs:** it starts at the first song, so it no longer
  covers the search box or the Shuffle button, and it rises to the top as you scroll. On a
  shorter screen its letters get a little smaller so all of them fit.
- About's footer is shorter.

## 0.5.14

- **Join a Radiance Music Server:** your own server, or a friend's. Settings → Servers → Join a
  server, then scan the invite's QR or open its link. The server's songs show in your Library under
  its name ("Bassi's server"): play them, save them on your phone with their parts and lyrics, add
  your own songs to it, and split songs there instead of on your phone.
- **Listening parties:** start or join one on a server, and everyone in it hears the same song at
  the same moment, wherever they are. A shared queue, who's listening, and reactions as it plays;
  the host plays, pauses and skips.
- The Mixer's and the Share sheet's buttons are in the app's own colours (a grey-purple showed).
- **The Mixer holds still:** it opens at its full height and stays there; scrolling it no longer
  sends it bouncing up and down. Close it with Done (or a tap above it, or Back).
- **Mixer sliders are easier to move:** a big round knob you can catch, and the sheet no longer
  pulls away under your finger.
- The Mixer's No vocals / Vocals only / Reset buttons go to a second line when the screen is
  narrow (Reset was squeezed to a letter a line).

## 0.5.13

- **A small download (about 43 MB, it was 208 MB):** the splitter's model isn't inside the app
  any more. The first time Radiance Music opens, it gets it by itself (165 MB, once) while the
  start screen shows how far along it is; nothing to press. If the internet drops it picks up
  where it stopped, and it's checked before it's used. Offline, it says so, with Try again or
  Open Radiance Music now (it finishes by itself when you're back online). Phones updating from
  an earlier version get it the same way, once, the first time they open this one.
- **It says what's wrong, in plain words:** no more raw error text. Notices at the top come
  with what to do (Retry, Connect, Open).
- **A song that won't play says why** (you're offline, YouTube wants to check you're not a
  robot, the song isn't on YouTube anymore, …) and goes on to the next one; after three in a
  row it stops and says so, with Retry. A spinner turns around Play while a song loads.
- **Offline shows:** "Offline · saved songs still play" above the player, "Back online" when
  the internet returns. Search, lyrics and the lyric picker say you're offline (with Connect)
  instead of "nothing found", and look again when you're back; liked songs that couldn't be
  saved offline are saved then.
- **The update pop-up:** when a new Radiance Music is out, Update now (it downloads, then
  Restart to update), Remind me later (a day) or Skip this version. Phones on 0.5.12 update
  to this one from About as before; the pop-up first shows for the version after it.

## 0.5.12

- **How to use it, the first time the app opens:** five cards to swipe through (search, like
  it and it's saved, split and mix, put it on your story, swipe and send), a few plain words
  each. Skip any time; Settings → How to use it shows them again.
- **The A to Z rail on Liked Songs**, as the computer's: tap a letter, or slide along the rail,
  to jump there (a big letter shows where you are); letters with no songs stay faint, and the
  letter of the songs at the top is lit.
- When the app updates itself, it adds one to the count of updates at radianceph.com (only
  the version: nothing about you), so we know how many people use each version.

## 0.5.11

- **Choosing lyrics, as Spotify's:** the lyrics big on the story's colour; tap a line and it
  lights up (no circles to tick). Lines next to each other, up to 150 characters, counted at
  the top; tapping a line away from them starts again from it, and tapping an end takes it off.
- **Every button has its icon:** Play your mix, Liked A to Z, Split this song, No vocals,
  Vocals only, Reset, Effects off, Update now, Show the QR, Scan a QR, Send, and the rest.

## 0.5.10

- **Share, as Spotify's:** a screen of its own with the story big. Cover or Lyrics (pick up to
  five lines), its colour (the cover's own colours, black, stone or sunset), and the apps on
  your phone in a row: Instagram Stories and Facebook Stories go straight to the story, then
  Messenger, WhatsApp, TikTok and others you have, Save, and More (Android's share menu).
- **Stories with the song's sound:** "With sound" makes the story a video (MP4) of the song
  itself, clipped to the lines you picked, each lighting up as it's sung, fading in and out
  (without lines: 15 seconds from where you are in the song). It's made on your phone in a
  few seconds. The sound comes from the song's saved copy, so a song you've liked.

## 0.5.9

- **Updates over the air:** the app looks for a newer version by itself (at most every six
  hours) and from About. Tap Update now: it downloads inside the app, checks that it really
  is Radiance Music, signed with the same key and newer, and Android installs it (it asks you
  to confirm, and once to allow updates from the app). A dot on Settings says one is out.
- **About:** the app's own page, as the computer app's: the version (an open beta), updates,
  what's new in this version and earlier ones, where to report a problem or email us, the code,
  and who the music, lyrics and parts come from.
- **Settings:** a gear beside Home and Library. Sharing songs is here now (Send songs, Get songs
  and Receive songs here), with how many cores splitting may use, the look (True Black or
  Stone) and About.
- **Now Playing, as Apple Music's:** the cover big (a little smaller while paused); swipe it
  left or right for the next or previous song, or down to close. A thin white bar with the time
  left, white controls without the orange circle, and a row of Lyrics (a quote bubble, not a
  mic), Mixer, Share and Queue. With lyrics on, the cover goes small beside the song and every
  line is as big, the sung one lit. Shuffle and Repeat are in the Queue.
- **Swipe the mini player** for the next or previous song too.
- **Library, roomier:** a big title, Liked Songs and History as pills, the search, then wide
  Play and Shuffle buttons, all scrolling away with your songs.
- **Home:** the artist under a card isn't cut off any more, and the top card is sunset, not
  purple.
- **The look is True Black or Stone everywhere** (no navy left): the sheets, cards and the
  splash.

## 0.5.8

- **Fixed: sending songs by QR.** Scanning a computer's or another phone's QR said "couldn't
  reach": Android blocks plain HTTP unless an app allows it, and a share between your devices
  uses it (on your own network, guarded by the share's own key). The app allows it now; what it
  asks of the internet stays HTTPS. When a device can't be reached, the reason goes in the log.

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
