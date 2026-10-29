# MediaDroid

[![License](https://img.shields.io/github/license/Hawkynt/MediaDroid)](https://github.com/Hawkynt/MediaDroid/blob/main/LICENSE)
[![Language](https://img.shields.io/github/languages/top/Hawkynt/MediaDroid?color=8957D5)](https://github.com/Hawkynt/MediaDroid)

[![CI](https://github.com/Hawkynt/MediaDroid/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Hawkynt/MediaDroid/actions/workflows/ci.yml)
![Last Commit](https://img.shields.io/github/last-commit/Hawkynt/MediaDroid?branch=main)
![Activity](https://img.shields.io/github/commit-activity/m/Hawkynt/MediaDroid)

[![Stars](https://img.shields.io/github/stars/Hawkynt/MediaDroid?color=FFD700)](https://github.com/Hawkynt/MediaDroid/stargazers)
[![Forks](https://img.shields.io/github/forks/Hawkynt/MediaDroid?color=008080)](https://github.com/Hawkynt/MediaDroid/network/members)
[![Issues](https://img.shields.io/github/issues/Hawkynt/MediaDroid)](https://github.com/Hawkynt/MediaDroid/issues)
![Code Size](https://img.shields.io/github/languages/code-size/Hawkynt/MediaDroid?color=4CAF50)
![Repo Size](https://img.shields.io/github/repo-size/Hawkynt/MediaDroid?color=FF9800)

[![Release](https://img.shields.io/github/v/release/Hawkynt/MediaDroid)](https://github.com/Hawkynt/MediaDroid/releases/latest)
[![Nightly](https://img.shields.io/github/v/release/Hawkynt/MediaDroid?include_prereleases&sort=date&filter=nightly-*&label=nightly&color=FF9800)](https://github.com/Hawkynt/MediaDroid/releases)
[![Downloads](https://img.shields.io/github/downloads/Hawkynt/MediaDroid/total)](https://github.com/Hawkynt/MediaDroid/releases)

> Your movies and shows are already organized — folders with `.nfo` files and cover art, scraped by Kodi or MediaBrowser over the years. MediaDroid plays that library straight from where it lives — local storage, a NAS share, an FTP server or a cloud folder — on any Android box, tablet, or phone: no media server, no account of ours, no re-scraping, no re-encoding. It reads what is already there and turns it into a browse-and-play app simple enough for a six-year-old and a grandparent.

![The browse wall: what is being watched as the hero, Continue watching, then every movie](screenshots/main.png)

## 🧭 Vision

Media libraries on Android are usually either a raw file browser or a client of someone else's server. MediaDroid claims the middle ground that home media collectors actually live in: the folders are the library, and the `.nfo` sidecars and `folder.jpg` covers you already scraped are the metadata — no database of ours in between. The bar for success is a household where nobody has to be taught the app: a kid finds a film by its picture, a grandparent resumes a show where they left off, and everything answers instantly even on cheap hardware. The parser, the scanner, the search and the binge logic are pure and tested; the screens sit on top of them and must never get slower than the hardware allows. The app scans a library, shows the wall, plays with resume and binge auto-advance, decodes exotic audio through a bundled FFmpeg extension, and paints missing art from the video files themselves.

## ✨ Features

- Reads `movie.nfo`, `tvshow.nfo`, and per-episode NFOs: plot, cast, runtime, rating, sets, trailer links.
- Covers from `folder.jpg` / `poster.jpg`, backdrops from `fanart.jpg`, logos and banners where present. A movie file's own `<name>.jpg` or `<name>-poster.jpg` is its poster; a season's cover is the `folder.jpg` in its folder or `seasonNN-poster.jpg` beside the show; episodes show their `<name>-thumb.jpg`.
- A long press on any poster or file — or holding a remote's OK — tells what it is about without opening it: the title, year, length and genres, the description, director, cast, studio and rating, and whether that comes from the NFO or the file's own tags.
- No art? The app pulls a still out of the video itself — episode thumbs and poster fallbacks, generated once and cached on device; from files Android cannot read itself (DV, RealMedia, WMV and the other containers the bundled FFmpeg demuxes) through FFmpeg. A show without a cover or backdrop is pictured by the first episode of its first regular season (never the specials), a season without a cover by its first episode's thumb or a still from it — on the wall and on the details screen alike.
- Shows without `Season NN` folders are grouped into virtual seasons from `SxxExx` / `NxNN` file names; titles fall back to the folder and file names. Absolute numbering without seasons — `E3`, `E03`, `EP003`, common for anime — is season 1, and inside a `Season NN` folder a bare `E03` belongs to that season.
- Release clutter in movie and show names is stripped: `The.Big.Short.2015.German.DL.1080p.BluRay.x264-GRP` reads `The Big Short (2015)`. Episodes are labelled `Episode 3`; a title comes from the episode NFO, or from the file name only in the clean forms `S01E03 - Title`, `1x03 - Title`, `E03 - Title` or `EP003 - Title`. Anything else stays `Episode 3` — rename the file or add an NFO to give it a title. An NFO title always wins.
- A movie without an NFO title takes the title stored inside the file (Matroska `Title`, MP4 `©nam`) before its cleaned-up file or folder name.
- Every video that is no episode is a movie, even without a year or an NFO (`Heat.mp4` is `Heat`); a folder holding one video is that movie, named after the folder. Trailers (`<name>-trailer.mkv`) and samples are skipped.
- Art in any picture format: JPEG, PNG, WebP, GIF, BMP, HEIC/HEIF and AVIF through Android's own decoders, TIFF (strips or tiles; LZW, Deflate or PackBits), TGA and PCX through the app's own — large scans are sampled down to the screen as they are read.
- Every container the player reads counts as a video: MP4 and QuickTime (`.mp4 .m4v .mov .qt .3gp .3g2 .f4v`), Matroska and WebM (`.mkv .mk3d .webm`), AVI (`.avi .divx .xvid`), MPEG program streams (`.mpg .mpeg .mpe .mp2 .m2p .vob`, and the camcorder and DVD-recorder `.mod .tod .vro`), MPEG transport streams (`.ts .m2ts .mts .m2t .tp .trp`), Ogg (`.ogv .ogm .ogx`) and Flash video (`.flv`) through Media3's own readers; and through the bundled FFmpeg's demuxers, for what Media3 cannot read, Windows Media (`.wmv .asf .wtv .dvr-ms`), RealMedia (`.rm .rmvb`), DV (`.dv .dif`), NUT, MXF, NuppelVideo (`.nuv`), raw MPEG video, H.264 and HEVC streams (`.m2v .mpv .264 .h264 .265 .h265 .hevc`), Y4M and IVF.
- Multi-part movies (`CD1`/`CD2`, `part1`/`part2`, `disc a`/`disc b`, as Kodi stacks them) are one movie that plays its parts one after the other, with the folder's cover.
- From NFOs: genres, an age badge in the familiar rating colours, and flags for the audio and subtitle languages listed in the stream details.
- AC3, E-AC3, DTS, TrueHD and other exotic audio tracks play through a bundled FFmpeg decoder, even on hardware without them.
- Binge mode, on out of the box: when an episode ends, the next one starts — season by season, in order. Intros are jumped over (also those after a cold open) and an episode ends where its credits begin, when the file's own chapters (Matroska / WebM, MP4 / MOV — Nero or QuickTime — and Ogg) or an edit decision list beside it mark them — the Kodi / MPlayer `.edl` that Jellyfin's Intro Skipper, Comskip and friends write (`<video>.edl`: `start end action` per line; a cut, commercial break or intro edit near the start is the intro, the last one after it is where the credits begin). Both can be switched off in Settings.
- Episode previews carry what the file holds: audio-language flags, subtitle languages, and badges for resolution (480p … 2K, 4K, 8K), HDR (HDR10, HDR10+, Dolby Vision, HLG), the video format and the best audio (Dolby Digital/Digital+/TrueHD/Atmos, DTS/DTS-HD MA/DTS:X, …) with its channels. They are read out of the video itself once and cached; the NFO's stream details only stand in when a file cannot be read. File names are never trusted for it.
- Watched ticks: every movie, every episode and every whole season has one, and the wall marks a movie or a show once all of it is watched; tapping it marks watched or unwatched, and that choice wins over a playcount in the NFO.
- Resume: every item remembers where you stopped. Once only the last two minutes or the credits are left — played up to there or skipped forward into them — the item counts as watched. Play on a show carries on with the episode in progress or the first one not yet watched; Continue watching shows the season and episode and a bar of how much is watched.
- The bottom of the sidebar shows the last thing played — its picture, title, episode and how far in — and one tap resumes it from any screen; collapsed, it is a play button over the picture.
- A sidebar on the left switches between Movies and TV Shows (plus Search, Filters and Settings); the content side shows only that selection. One tap collapses it to an icon rail and expands it again, and it remembers how you left it.
- The home of each section reads like the streaming apps everyone already knows: a backdrop hero for one title with Play and More info, a Continue watching row, then the poster wall. Works with touch, a mouse, or a TV remote's D-pad.
- The media folder is chosen with Android's own folder picker — internal storage, SD card or USB drive — with a typed path as the fallback for boxes that ship no picker.
- A title's details open with its backdrop across the top, never taller than 40 % of the screen, so the title, Play and the start of the plot are in view in portrait and landscape alike.
- No database: the folders are the library. It is re-read every time the app opens or comes back to the foreground, so a film copied onto the SD card is simply there. Watched marks and resume positions are the only things the app keeps. Coming back from a title's details lands at the same scroll position.
- Dark cinema theme and its own launcher icon, adaptive and themed-icon ready.
- Subtitles on/off with a preferred language, and a picker for every audio and subtitle track a file carries — and for the subtitle and audio files beside it named after it, as Kodi reads them: `Heat (1995).de.srt`, `Heat (1995).en.forced.ass`, `Heat (1995).en.sdh.vtt` (SubRip, SSA / ASS, WebVTT, TTML), `Heat (1995).de.ac3`, `Heat (1995).en.commentary.mka` (AC3 / E-AC3, AAC, MP3, FLAC, Opus, Vorbis, WAV, Matroska audio); the name gives the language and marks forced and hard-of-hearing subtitles. An audio file that cannot follow the film's seeks is dropped rather than stopping it.
- Holding skip forward or back — on screen, or a remote's left/right and its rewind / fast-forward keys — steps 10 s, then 30 s after three seconds of holding, 60 s after six and five minutes after nine; the target time shows while holding and letting go seeks there once. A short tap stays the ordinary skip.
- Child lock in the gear: once locked, every touch, swipe, Back and button is ignored — only the volume keys still work — and any touch shows a lock symbol for a moment; holding it for two seconds unlocks.
- Picture in picture: leaving the app while a film plays on the phone shrinks it into a small window in the picture's own shape; tapping it brings the full player back where it was. Leaving while paused — or closing the small window — pauses the film instead of letting it play on unseen.
- An info button in the player's top bar shows what is playing: what it is about (the title, the NFO's facts and description, else the file's own), the tags Media3 reads from the file, the file, its container and size, every video / audio / subtitle track with codec, language, channels, resolution and frame rate (the ones playing marked), and how it plays, drawn as a graph like an audio player's signal path — a numbered card per file read, then a lane each for picture, sound and subtitles, its stages as cards joined by arrows down to the screen or the speaker, side by side where the screen is wide enough: for each file where it is read from (the device, an SMB, NFS, FTP, SFTP or WebDAV share, a document folder), which demuxer splits it (one of Media3's or FFmpeg's), and for picture and sound every stage up to the screen and the speaker — the decoder (hardware, Android's software or the bundled FFmpeg), conversion, pixel shape, picture filters and framing, renderer and screen with its resolution and refresh rate; loudness, night mode, speed, delay and spatial audio, resampling, down- or upmixing, the AudioTrack's format (or passthrough, or offload) and the output device; the subtitles' format, timing and renderer — with the pixel format (FFmpeg's own, or the stream's chroma and bit depth), colour space, transfer and range, and the sample format the sound is decoded and processed in, at every step. Sidecar files the player reads besides the video are further inputs, each lane marked with the file it comes from.
- Media controls everywhere: play/pause, next and previous work from headset and Bluetooth buttons, the media notification and the lock screen while the player is open; next and previous follow the episodes or the parts of a movie. Android 13+ asks once for the notification.
- Rotation in the gear: auto, landscape or portrait, held for the session; leaving the player hands rotation back to the system.
- Night mode in the gear compresses the dynamic range in the app's own audio stage — quiet dialogue comes up, explosions come down — for late evenings and thin walls; it combines with the loudness gain and the volume boost (soft-knee compressor after Giannoulis, Massberg & Reiss, JAES 2012).
- Audio delay in the gear, −2.5 s … +2.5 s in 50 ms steps, fixes lip-sync; the gear says it in words — "sound 150 ms after the picture", "subtitles 2 s before the picture" — and its buttons are Sooner and Later, for the sound as for the subtitles; it is remembered per audio output — the phone's speaker, each Bluetooth headset, USB, HDMI — since each has its own latency, and follows the output when it changes.
- Subtitles in the gear: a delay of −10 … +10 s in tenths of a second, four sizes, a raised position above burned-in text and an optional dark box behind the text. The household sets the look in the guarded settings; each video can keep its own look and delay.
- On phones with a notch or punch hole (Android 9+), a switch under the picture sizes lets the picture reach into the camera cutout — mostly useful with Fill; it is remembered.
- Spatial audio: on Android 13+ devices with a spatializer, a remembered switch in the gear hands multichannel sound to it for headphones that support it. The player takes audio focus like any film player: other apps pause, a call pauses the film.
- Sleep timer in the gear: off, 15, 30, 45, 60 or 90 minutes, or at the end of this video. While it runs the top bar shows the minutes left; when it runs out the sound fades over ten seconds and playback pauses. It never carries over to the next start.
- Repeat in the gear: off, this video, or all of it (the show or the folder, round again from the start) — for the film a child wants to see again and again. It lasts for the session.
- With the controls up, the bar also tells when the film will be over on the clock ("Ends 21:35", in the device's 12/24-hour format), allowing for the playback speed.
- The player shows nothing but the picture: a tap brings the controls up, a few seconds untouched fade them out, and the status and navigation bars stay hidden while watching.
- Even frames, in Settings: where the screen cannot switch to the film's frame rate, the film plays a little faster or slower so every frame stays on screen equally long — 23.976 fps as 24 on 120 Hz, 25 fps as 30 or 20 on 60 Hz — slight (up to 5 %) or strong (up to 25 %), towards the nearest rate, only faster or only slower; the sound keeps its pitch.
- For TV boxes, two guarded settings: matching the display's refresh rate to the film's frame rate (or a whole multiple — no judder at 23.976 fps), and audio passthrough that hands AC3/E-AC3/DTS/TrueHD untouched to an AV receiver over HDMI where the device supports it.
- Video plays on the device's own hardware decoders; the bundled FFmpeg decoders only step in for what they cannot play — WMV, VC-1, RealVideo, DV, MS-MPEG4 and whatever a device lacks — in software. Settings can put software decoding first, with a warning: it drains the battery fast and is meant for videos that otherwise do not play at all or only with errors.
- The player's gear holds playback speed from 0.2× to 5× (starting at the default speed), subtitles and track pickers, and brightness/gamma/contrast/color for the video on screen — remembered per video, live as you drag, and the picture size: Fit, Fill (crop to the screen) or Stretch. Filters beside them — sharpness and clarity (unsharp masking on the luma, small and large radius) and denoise in three strengths (a bilateral filter that smooths noise and MPEG blocks but keeps edges), and deinterlacing — Auto (rebuilds the second field's lines only where the picture combs, so progressive video passes untouched), Blend or Bob — and upscaling for pictures smaller than the screen — Standard, Lanczos-3, or AMD FidelityFX Super Resolution 1 (edge-adaptive upscaling and contrast-adaptive sharpening) — — run on the graphics chip in a stage of the app's own between decoder and screen, for the device's decoders and the bundled FFmpeg alike, on the phone and on a second screen; without a filter set the picture goes straight to the screen, and HDR videos skip them.
- Touch on the bare picture: double tap left or right jumps 15 s back or ahead, a vertical swipe on the left half sets the screen brightness and on the right half the volume — both run on past the device's maximum up to 800 %, doubling every third of the extra stretch: louder through the app's loudness gain (soft-saturating, so quiet tracks come up without cracking peaks), brighter through a gamma lift — and a pinch zooms up to 4× and pans the zoomed picture.
- Next and Previous on the controls move through the episodes or the parts of a movie; Previous restarts the current one when it is more than 3 s in.
- Turning the device keeps the player going: same video, same position, same open panel.
- While the phone's own screen is mirrored to a TV (Smart View and the like), the player keeps its bars and the gear panel inside the centred 16:9 part a TV shows, so nothing is cut off.
- With a second screen attached (HDMI, a USB-C dock, a wireless display) a button sends the video alone there; the phone keeps the controls. Leaving the app then opens no small window: the film plays on there in the background, kept alive by the playback notification, and the phone's whole screen is free for anything else.
- Parent-guarded settings behind a four-digit PIN, freshly drawn on every visit and shown on screen as words — a child who cannot read cannot get in, and nobody has to remember anything. There: default speed, loudness boost for unnormalized audio, the default brightness/gamma/contrast/color every video starts from, subtitle and stream languages, and a maximum-age mode that hides content whose NFO certification is too old (FSK, MPAA and TV ratings understood).
- Instant search across the whole library.
- Three ways to lay out a wall, switched at its head and remembered: posters, a compact list (small poster, title, year and runtime or seasons, genres, age badge and the quality badges probed from the file) and a details list that adds two lines of plot — for remotes and for libraries too big to scan by pictures.
- Every poster and list row — on the walls and in search — carries the watched tick and, while something is under way, a resume bar along its picture: the movie part or the show's episode with a resume point.
- More rows under Continue watching: *Recently added* (the NFO's `<dateadded>`, else the file's date on disk, newest first), *Not watched yet* and, for shows, *New episodes* (shows whose newest episode is unwatched, newest first). Each scrolls sideways and ends in *See all*, which opens the whole row as a wall; Back returns to the home.
- Walls of 30 titles or more in title order get an A–Z rail along the edge; touching or dragging along it jumps to the letter (accents fold in, `Ä` is `A`; digits are `#`), and the letter shows big while dragging.
- The same film found more than once — the same title and year, say 1080p and 4K, or two language cuts — is one card with a small file count. Its details list every file with its resolution, HDR, audio and language badges and size, each one tap from playing; Play takes the one with a resume point, else the best picture (resolution, then HDR, then the bigger file), and a watched mark on any of them counts for the film.
- Filter by genre and year, group by decade, facet counts as you browse.
- A film's details add, where the NFO has them, the rating, release date, director and studio; a *Trailer* button when a `<name>-trailer.*` sits beside it (trailers stay off the wall); and descriptions for the show, each season (its `season.nfo`) and each episode, under the play button; and a *File* section, folded away until opened, with the path, size, container and every video, audio and subtitle track named by the codec the file holds it in, with its profile and level where the file says (`H.264 · High@L4.1`, `HEVC · Main 10@L5.1`, `AAC · HE-AAC`) — what is in the file, not what plays it.
- Settings list every source the library is read from, each with its own name: the media folder chosen at the start, further folders on the device, Windows / Samba shares (SMB 2/3, with user and password or as guest; servers announcing themselves on the local network are offered with one tap), NFS exports (NFSv3, by export path and user id; the export must allow connections from non-privileged ports, as an app cannot use the privileged ones), FTP and FTPS (passive mode) SFTP (password or SSH key; the server's host key is kept on first use and a changed one refused) WebDAV folders — Nextcloud, ownCloud, NAS web shares — by their address, user and password, each tested before saving; and cloud folders — Google Drive, OneDrive, Dropbox, Nextcloud and anything else that installs an Android document provider — picked in the system's own folder picker like a local folder, so the app itself stores no cloud password. Each source's scan is kept on the device: the walls appear at once on start and offline, and a rescan reads again only the NFOs and EDLs that changed (by size and modification time). A source that cannot be reached keeps its titles on the walls, greyed out with "not reachable right now". Videos on a source stream with up to two minutes buffered ahead, so 4K over Wi-Fi rides out the dips; resume, binge, intro and credit skipping (chapters and EDLs), badges and thumbnails work as for local files, and frames and badges are probed once and cached. The walls merge them — a film found on two sources is one card with both files — and videos on a share stream straight from it, with their posters and thumbs fetched once and kept small on the device. Passwords live encrypted under a key in the Android Keystore, never in the state file; a source that cannot be reached shows as "not reachable right now" in Settings.
- A Files entry in the sidebar browses storage as it really is, like a file manager: the internal storage, SD cards and USB drives, not only the media library. Nothing is renamed or grouped — folders and files keep their real names — but what sits beside them counts: a video's NFO gives it its title, plot, age badge and watched state, `folder.jpg` / `poster.*` is a folder's cover and `<name>.jpg`, `<name>-poster.*` or `<name>-thumb.*` a video's picture, falling back to a frame from the video. Without an NFO, the title and description the file carries itself (Matroska's segment title, MP4's name and description atoms) name and describe it. Folders come first, then videos with their picture, duration, size and quality badges, sorted by name (as numbers read, `2` before `10`), date or size. Tapping a video plays it with resume; binge mode goes on to the next video in the folder in that order, and Play on a folder starts with its first. Every saved network and cloud source is listed beside the volumes and browsed the same way.
- Genres and Years are places in the sidebar too: a list of genres or decades with their counts and a strip of posters each, for Movies or TV Shows; tapping one opens that wall narrowed to it, with *Clear filters* to get back.
- Sort each wall its own way, remembered per section: title A–Z or Z–A, newest or oldest, recently added (the NFO's `<dateadded>`, else the file's time), runtime shortest or longest first and, where NFOs carry it, best rated; TV shows add recently updated, the show with the newest episode first.
- Big touch targets and high contrast — usable by kids and older users without glasses-or-guessing.
- Local libraries work fully offline. No account of ours and no telemetry; a network share or cloud is contacted only once you add it as a source, with the credentials you give it, kept in the device's keystore.

## 🧩 Support matrix

| Source | Read | Write | Notes | Reference |
| --- | :---: | :---: | --- | --- |
| [Movie NFO](https://kodi.wiki/view/NFO_files/Movies) | ✅ | — | Title, plot, runtime, year, genres, set, cast, IDs, rating, date added | [Kodi NFO docs](https://kodi.wiki/view/NFO_files) |
| [TV show NFO](https://kodi.wiki/view/NFO_files/TV_shows) | ✅ | — | `tvshow.nfo` per show folder | [Kodi NFO docs](https://kodi.wiki/view/NFO_files) |
| Episode NFO | ✅ | — | Named after its video file; season/episode may also come from the name | [Kodi NFO docs](https://kodi.wiki/view/NFO_files) |
| NFO stream details | ✅ | — | `<fileinfo><streamdetails>` audio and subtitle languages (3-letter codes) shown as flags | [Kodi NFO docs](https://kodi.wiki/view/NFO_files/Movies) |
| Multi-part movies | ✅ | — | Kodi's default `cd`/`dvd`/`part`/`pt`/`disk`/`disc` + number or a–d stacking | [Kodi advancedsettings.xml](https://kodi.wiki/view/Advancedsettings.xml#moviestacking) |
| `SxxExx` / `NxNN` file names | ✅ | — | The prevailing rip and scraper naming | — |
| Art: `folder.jpg`, `poster.jpg`, `fanart.jpg`, banner, clearlogo, discart | ✅ | — | Numbered variants (`poster-1.jpg`) count | [Kodi art conventions](https://kodi.wiki/view/Artwork) |
| Containers & codecs | ✅ | — | Video on the device's own decoders (MP4/MKV, H.264, HEVC/AV1 per device), falling back to bundled FFmpeg decoders in software; audio falls back to FFmpeg for AC3, E-AC3, DTS, TrueHD, MLP, WMA, RealAudio and more; FFmpeg's demuxers read ASF/WMV, RealMedia, DV, NUT, MXF and raw streams that Media3 cannot | [Media3 FFmpeg extension](https://github.com/androidx/media/tree/release_v1.11.1/libraries/decoder_ffmpeg), [FfmpegVideoRenderer PR](https://github.com/androidx/media/pull/1591), [`tools/ffmpeg`](tools/ffmpeg/README.md), [Android formats](https://developer.android.com/guide/topics/media/media-formats) |

`✅` full · `⚠️` partial, explained in the row · `—` unsupported.

## 📦 Installation

Android 8.0 (API 26) or newer, phone, tablet, or TV box. Not on the Play Store (yet): download the APK from [Releases](https://github.com/Hawkynt/MediaDroid/releases) — a dated stable build or a `nightly-*` build. Take `MediaDroid.apk`, the signed release build, and enable installation from unknown sources; `MediaDroid-debug.apk` is for development and cannot update over it. Storage stays on your device; the app needs access only to the folders you point it at.

## 🚀 Quick start

```bash
adb install MediaDroid.apk
```

Open MediaDroid, grant file access, tap **Choose media folder** and pick the root of your media folders in the system folder picker: the library is read, the sidebar opens on Movies, and the wall fills with covers, and plots, seasons and episode thumbs come from the `.nfo` files already sitting there. Tapping a show offers Play next — episodes run in order and mark themselves watched.

## 🖼️ Screenshots

Generated documentation: every push to a working branch renders the real screens on the JVM
(Robolectric + Roborazzi) from a demo library of invented titles whose posters, backdrops and episode
thumbs are painted from the titles themselves, and commits whatever changed back onto the branch. Run
`./gradlew :app:recordRoborazziDebug -Pscreenshots` to render them locally.

![A show's details: backdrop, Play next, seasons and episodes with thumbs, language flags and watched ticks](screenshots/detail.png)
![The player on an episode of a binge queue: previous and next episode, the gear for speed and picture](screenshots/player.png)
![Filters and facets: genres with counts, decades and sort order](screenshots/filters.png)
![The Files browser in a season folder: real file names, NFO titles, thumbs, watched ticks and a resume bar](screenshots/files.png)

## 🗺️ Roadmap

### Player

Specified, not built yet. Prior art: [Nova Video Player](https://github.com/nova-video-player/aos-Video) (Apache-2.0), looked at as a user would and read for its menus; only what fits a household player without accounts or a network made it here.

- **Night mode.** A switch in the gear compresses the dynamic range in the app's audio stage: quiet dialogue comes up, explosions come down, for late evenings and thin walls. It combines with the loudness gain and the volume boost.
- **Child lock.** A lock in the gear freezes the player: every touch and swipe is ignored, a small lock symbol shows briefly on any touch, and only holding that symbol for two seconds unlocks. Volume keys keep working.
- **Audio delay.** In the gear, −2.5 s … +2.5 s in 50 ms steps to fix lip-sync; remembered per audio output (the phone's speaker, each Bluetooth headset, HDMI), since each has its own latency.
- **Spatial audio.** On Android 13+ a switch in the gear hands multichannel sound to the platform spatializer for headphones that support it.
- **Display cutout.** In Fill mode the picture may extend into the camera notch / punch hole, as a switch next to the picture sizes.

### Navigation and browsing

From the same look at Nova's library side: what it browses, what an item tells about itself, and where.


Considered and left out: online scraping, playlists, and IMDb/TMDb links — the metadata stays what is already in the folders.

### Sources

The library may live anywhere a household keeps its folders. Every source below is browsed like the SD card is today, and can be the media root (or one of several roots) the scanner reads, with the same NFO and art conventions.


## 🛠️ Building

Requires JDK 21 and an Android SDK with platform 37; `local.properties` must point `sdk.dir` at it.

```bash
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # parser, library, and binge-logic unit tests
```

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) — commit messages carry `+ - * # !` prefixes because the changelog is generated from them, and pull requests run the full gate.

## 🆘 Getting Help

[Open an issue](https://github.com/Hawkynt/MediaDroid/issues): say which device and Android version, what you pointed the app at, and what you expected instead. Library-parsing bugs should name the folder layout.

## ❤️ Support

If this project saves you time or money, consider supporting its development:

[![GitHub Sponsors](https://img.shields.io/badge/GitHub-Sponsor-EA4AAA?logo=githubsponsors)](https://github.com/sponsors/Hawkynt)
[![PayPal](https://img.shields.io/badge/PayPal-Donate-00457C?logo=paypal)](https://www.paypal.me/hawkynt)

## 📜 License

Licensed under LGPL-3.0-or-later — see [LICENSE](LICENSE).
