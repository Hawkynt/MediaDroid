package com.hawkynt.mediadroid.state

import com.hawkynt.mediadroid.library.FileSort
import com.hawkynt.mediadroid.library.FolderDefaults
import com.hawkynt.mediadroid.library.FolderPlace
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.SortOrder
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.library.WallMode
import com.hawkynt.mediadroid.library.WatchMarks
import com.hawkynt.mediadroid.player.AspectOverride
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.SubtitleLook
import kotlinx.serialization.json.Json

/** Where the household's memory lives: one small text file, read once and written whole on every change. */
interface StateFile {
    /** The file's text; null when there is none yet. */
    fun read(): String?

    /** Replaces the file's text with [text]. */
    fun write(text: String)
}

/**
 * Who is told the memory was read and changed: a screen reading it subscribes to it, and is drawn again when it
 * changes (each app's UI fills this in; tests and tools need nobody).
 */
interface StateWatcher {
    /** [StateStore.data] was read. */
    fun read()

    /** [StateStore.data] changed and was saved. */
    fun changed()

    companion object {
        /** Nobody watches. */
        val None = object : StateWatcher {
            override fun read() = Unit
            override fun changed() = Unit
        }
    }
}

/**
 * The app's whole memory: watched set, resume positions, library root, pictures, settings. One small JSON file — no
 * database, nothing to migrate, survives app updates; the same model on every platform. Keys are video locations,
 * stable as long as the folder is.
 */
class StateStore(private val file: StateFile, private val watcher: StateWatcher = StateWatcher.None) {

    private val current: AppData = load()

    /** The memory itself; changes made to it are kept by [save]. */
    val data: AppData
        get() {
            watcher.read()
            return current
        }

    private fun load(): AppData = runCatching { file.read() }.getOrNull()?.let(::decode) ?: AppData()

    /** Writes the memory out and tells the watcher. */
    fun save() {
        runCatching { file.write(encode(current)) }
        watcher.changed()
    }

    /** The household's watched marks over what the NFOs say; reading it subscribes a screen to changes. */
    val marks: WatchMarks get() = WatchMarks(data.watched, data.unwatched)

    /** Whether [videoPath] counts as watched; [nfoWatched] is what its NFO says. */
    fun isWatched(videoPath: String, nfoWatched: Boolean = false): Boolean = marks.isWatched(videoPath, nfoWatched)

    /**
     * Marks a video watched as it finishes playing; cheap to call again and again. Watched already, it still drops a
     * resume point the video kept from a later look — or it would stay under Continue watching for good.
     */
    fun markWatched(videoPath: String) {
        if (videoPath !in current.watched) {
            setWatched(listOf(videoPath), true)
        } else if (current.resume.remove(videoPath) != null) {
            save()
        }
    }

    /** Ticks [videoPaths] off as watched (dropping their resume points) or unticks them. */
    fun setWatched(videoPaths: Collection<String>, watched: Boolean) {
        WatchMarks(current.watched, current.unwatched).set(videoPaths, watched)
        if (watched) videoPaths.forEach { current.resume.remove(it) }
        save()
    }

    /**
     * Remembers where [videoPath] stopped. Near the end counts as finished, no point resuming the last seconds: half
     * a percent of the runtime, capped at 30 s, so short clips still resume. A new resume point needs [playedMs] —
     * the time actually played this time — of [MIN_PLAYED_MS] (half the runtime for a short clip): a look of some
     * seconds into an episode is nothing to continue. One already under way moves on however short the stretch.
     */
    fun saveResume(videoPath: String, positionMs: Long, durationMs: Long, playedMs: Long = Long.MAX_VALUE) {
        if (durationMs <= 0 || positionMs <= 0) return
        if (videoPath !in data.resume && playedMs < minOf(MIN_PLAYED_MS, durationMs / 2)) return
        val tail = minOf(30_000L, durationMs / 20)
        if (durationMs - positionMs < tail) {
            data.resume.remove(videoPath)
        } else {
            data.resume[videoPath] = ResumeInfo(positionMs, durationMs)
        }
        save()
    }

    fun resumeFor(videoPath: String): ResumeInfo? = data.resume[videoPath]

    /** Forgets where [videoPath] stopped: it leaves Continue watching and plays from the start next time. */
    fun forgetResume(videoPath: String) {
        if (data.resume.remove(videoPath) != null) save()
    }

    /** The lip-sync delay remembered for the audio output [outputKey], in ms. */
    fun audioDelayFor(outputKey: String): Int = data.audioDelays[outputKey] ?: 0

    fun saveAudioDelay(outputKey: String, delayMs: Int) {
        if (delayMs == 0) data.audioDelays.remove(outputKey) else data.audioDelays[outputKey] = delayMs
        save()
    }

    /** The order the [section]'s wall was last sorted by; A–Z until one is chosen. */
    fun sortFor(section: LibrarySection): SortOrder =
        data.sorts[section.name]?.let { name -> SortOrder.entries.firstOrNull { it.name == name } } ?: SortOrder.TITLE

    /** Posters, list or details, as the walls were last laid out. */
    val wallMode: WallMode get() = WallMode.entries.firstOrNull { it.name == data.wallMode } ?: WallMode.POSTERS

    /** How the Files browser orders videos, as last chosen. */
    val filesSort: FileSort get() = FileSort.entries.firstOrNull { it.name == data.filesSort } ?: FileSort.NAME

    /**
     * Every place the library is read from. Until a second one is added, that is the media folder
     * chosen at the start, as a local source.
     */
    val sources: List<Source>
        get() = data.sources.ifEmpty {
            listOfNotNull(
                data.rootPath.takeIf { it.isNotEmpty() }?.let { Source.local(PRIMARY_SOURCE, "Media folder", it) },
            )
        }

    fun saveSources(sources: List<Source>) {
        data.sources.clear()
        data.sources.addAll(sources)
        sources.firstOrNull { it.kind == SourceKind.LOCAL }?.let { data.rootPath = it.path }
        save()
    }

    fun saveFilesSort(sort: FileSort) {
        data.filesSort = sort.name
        save()
    }

    fun saveWallMode(mode: WallMode) {
        data.wallMode = mode.name
        save()
    }

    fun saveSort(section: LibrarySection, order: SortOrder) {
        data.sorts[section.name] = order.name
        save()
    }

    /** The household's default picture, from the guarded settings. */
    fun defaultPicture(): PictureAdjustment = with(data.settings) {
        PictureAdjustment(
            brightness, gamma, contrast, saturation, sharpness, clarity,
            noise ?: PictureAdjustment.noiseOfLevel(denoise), deinterlaceOf(deinterlace),
            upscalerOf(upscaler), interpolationOf(interpolation), fieldRate,
            upscalerStrength = upscalerStrength,
            deband = deband,
            cropBlackBars = cropBlackBars,
            chroma = chromaOf(chroma),
            upscalerModel = upscalerModel,
            upscalerTta = upscalerTta,
            deblock = deblock,
            dering = dering,
            shader = shader,
            temporalDenoise = temporalDenoise,
            dotCrawl = dotCrawl,
            colorize = colorizeOf(colorize),
        )
    }

    /**
     * Keeps [picture] as the household's default — what it offers for every video: not the 3D eye, the shape, the
     * station logo or the colour system, each a video's or a folder's own.
     */
    fun saveDefaultPicture(picture: PictureAdjustment) {
        data.settings.apply {
            brightness = picture.brightness
            gamma = picture.gamma
            contrast = picture.contrast
            saturation = picture.saturation
            sharpness = picture.sharpness
            clarity = picture.clarity
            noise = picture.noise
            deinterlace = picture.deinterlace.name
            upscaler = picture.upscaler.name
            interpolation = picture.interpolation.name
            fieldRate = picture.fieldRate
            upscalerStrength = picture.upscalerStrength
            deband = picture.deband
            cropBlackBars = picture.cropBlackBars
            chroma = picture.chroma.name
            upscalerModel = picture.upscalerModel
            upscalerTta = picture.upscalerTta
            deblock = picture.deblock
            dering = picture.dering
            shader = picture.shader
            temporalDenoise = picture.temporalDenoise
            dotCrawl = picture.dotCrawl
            colorize = picture.colorize.name
        }
        save()
    }

    /**
     * The picture for [videoPath]: its own correction if one was set, else its folder's (the nearest folder or share
     * above it with a picture of its own, see [FolderDefaults]), else the household's default — with its show's station
     * logo hidden.
     */
    fun pictureFor(videoPath: String): PictureAdjustment = FolderDefaults.resolve(
        ownPictureOf(videoPath),
        folderPictureFor(videoPath),
        defaultPicture(),
    ).copy(logo = logoFor(videoPath))

    /** The correction [videoPath] keeps for itself, if any. */
    fun ownPictureOf(videoPath: String): PictureAdjustment? = data.picture[videoPath]?.let(::adjustmentOf)

    /** The station logo hidden for [videoPath]'s show, or null. */
    fun logoFor(videoPath: String): LogoMask? = data.logos[showOf(videoPath)]?.let {
        runCatching {
            LogoMask(LogoMask.Corner.valueOf(it.corner), it.size, LogoMask.Mode.valueOf(it.mode))
        }.getOrNull()
    }

    /** Remembers [logo] (none: null) for [videoPath]'s show, every video in its folder. */
    fun saveLogo(videoPath: String, logo: LogoMask?) {
        keepLogo(videoPath, logo)
        save()
    }

    private fun keepLogo(videoPath: String, logo: LogoMask?) {
        if (logo == null) {
            data.logos.remove(showOf(videoPath))
        } else {
            data.logos[showOf(videoPath)] = LogoOverride(logo.corner.name, logo.size, logo.mode.name)
        }
    }

    /** What [videoPath] starts from without a picture of its own: its folder's, else the household's default. */
    fun basePictureFor(videoPath: String): PictureAdjustment = folderPictureFor(videoPath) ?: defaultPicture()

    /** The folder or share whose picture [videoPath] takes, if any does: the nearest above it with one. */
    fun pictureFolderOf(videoPath: String): FolderPlace? =
        FolderDefaults.nearest(folderPictures(), FolderDefaults.placeOf(sources, videoPath))?.first

    /** The picture [videoPath]'s folder or share gives it, if any above it has one. */
    fun folderPictureFor(videoPath: String): PictureAdjustment? =
        FolderDefaults.nearest(folderPictures(), FolderDefaults.placeOf(sources, videoPath))?.second

    /** Every folder or share with a picture of its own. */
    fun folderPictures(): Map<FolderPlace, PictureAdjustment> = data.folderPictures.mapNotNull { (key, kept) ->
        FolderPlace.of(key)?.let { it to adjustmentOf(kept) }
    }.toMap()

    /**
     * Keeps [picture] as the picture every video in [place] starts with (the 3D eye and the shape stay each video's
     * own); null forgets the folder's picture.
     */
    fun saveFolderPicture(place: FolderPlace, picture: PictureAdjustment?) {
        if (picture == null) {
            data.folderPictures.remove(place.key)
        } else {
            val shared = picture.copy(stereo = PictureAdjustment.Stereo.OFF, aspect = AspectOverride.AUTO, logo = null)
            data.folderPictures[place.key] = overrideOf(shared)
        }
        save()
    }

    /** The folder [videoPath] lies in, as [FolderDefaults] keeps it. */
    fun folderOf(videoPath: String): FolderPlace = FolderDefaults.placeOf(sources, videoPath)

    /**
     * Remembers [picture] for [videoPath]; what it would start from anyway — its folder's picture, else the
     * household's default — (or null) drops the video's own entry.
     */
    fun savePicture(videoPath: String, picture: PictureAdjustment?) {
        // The logo is the show's, kept for its folder; the rest is the video's own.
        keepLogo(videoPath, picture?.logo)
        val own = picture?.copy(logo = null)
        if (own == null || own == basePictureFor(videoPath)) {
            data.picture.remove(videoPath)
        } else {
            data.picture[videoPath] = overrideOf(own)
        }
        save()
    }

    /** How the output screen [key] is set up; the default until it is set. */
    fun screenSetup(key: String): ScreenSetup =
        data.settings.screens[key]?.let {
            ScreenSetup.of(it.shape, it.mirror, it.flip, it.keystoneVertical, it.keystoneHorizontal, it.burnInCare)
        } ?: ScreenSetup.Default

    /** Keeps [setup] for the output screen [key]; the default drops its entry. */
    fun saveScreenSetup(key: String, setup: ScreenSetup) {
        data.settings.screens = if (setup == ScreenSetup.Default) {
            data.settings.screens - key
        } else {
            data.settings.screens + (
                key to ScreenOverride(
                    setup.shape.name, setup.mirror, setup.flip, setup.keystoneVertical, setup.keystoneHorizontal,
                    setup.burnInCare,
                )
            )
        }
        save()
    }

    /** The household's subtitle look, from the guarded settings. */
    fun defaultSubtitleLook(): SubtitleLook = SubtitleLook(
        size = runCatching { SubtitleLook.Size.valueOf(data.settings.subtitleSize) }
            .getOrDefault(SubtitleLook.Size.NORMAL),
        raised = data.settings.subtitleRaised,
        box = data.settings.subtitleBox,
    )

    fun subtitleLookFor(videoPath: String): SubtitleLook = data.subtitles[videoPath]?.let {
        SubtitleLook(
            delayMs = it.delayMs,
            size = runCatching { SubtitleLook.Size.valueOf(it.size) }.getOrDefault(SubtitleLook.Size.NORMAL),
            raised = it.raised,
            box = it.box,
        )
    } ?: defaultSubtitleLook()

    /** Remembers [look] for [videoPath]; the household default (or null) drops the video's own entry. */
    fun saveSubtitleLook(videoPath: String, look: SubtitleLook?) {
        if (look == null || look == defaultSubtitleLook()) {
            data.subtitles.remove(videoPath)
        } else {
            data.subtitles[videoPath] = SubtitleOverride(look.delayMs, look.size.name, look.raised, look.box)
        }
        save()
    }

    companion object {
        /** How long a video has to play before it is worth continuing: three minutes (see [saveResume]). */
        const val MIN_PLAYED_MS = 180_000L

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /** The memory as the file keeps it. */
        fun encode(data: AppData): String = json.encodeToString(AppData.serializer(), data)

        /** The memory in [text]; null when it is not JSON or not this memory. */
        fun decode(text: String): AppData? = runCatching { json.decodeFromString(AppData.serializer(), text) }.getOrNull()

        /** The folder a video's station logo is remembered for: its show's (either slash). */
        fun showOf(videoPath: String): String = videoPath.replace('\\', '/').substringBeforeLast('/', "")

        /** A kept picture as the player takes it; a name this version does not know reads as the neutral choice. */
        fun adjustmentOf(it: PictureOverride): PictureAdjustment = PictureAdjustment(
            it.brightness,
            it.gamma,
            it.contrast,
            it.saturation,
            it.sharpness,
            it.clarity,
            it.noise ?: PictureAdjustment.noiseOfLevel(it.denoise),
            deinterlaceOf(it.deinterlace),
            upscalerOf(it.upscaler),
            interpolationOf(it.interpolation),
            it.fieldRate,
            runCatching { PictureAdjustment.Stereo.valueOf(it.stereo) }.getOrDefault(PictureAdjustment.Stereo.OFF),
            it.upscalerStrength,
            it.deband,
            it.cropBlackBars,
            AspectOverride.entries.firstOrNull { a -> a.name == it.aspect } ?: AspectOverride.AUTO,
            chroma = chromaOf(it.chroma),
            upscalerModel = it.upscalerModel,
            upscalerTta = it.upscalerTta,
            deblock = it.deblock,
            dering = it.dering,
            shader = it.shader,
            temporalDenoise = it.temporalDenoise,
            dotCrawl = it.dotCrawl,
            colorize = colorizeOf(it.colorize),
            colourSystem = runCatching { PictureAdjustment.ColourSystem.valueOf(it.colourSystem) }
                .getOrDefault(PictureAdjustment.ColourSystem.AUTO),
        )

        /** A picture as kept, the choices by name. */
        fun overrideOf(picture: PictureAdjustment): PictureOverride = PictureOverride(
            picture.brightness,
            picture.gamma,
            picture.contrast,
            picture.saturation,
            picture.sharpness,
            picture.clarity,
            0,
            picture.noise,
            picture.deinterlace.name,
            picture.upscaler.name,
            picture.interpolation.name,
            picture.fieldRate,
            picture.stereo.name,
            picture.upscalerStrength,
            picture.deband,
            picture.cropBlackBars,
            picture.aspect.name,
            chroma = picture.chroma.name,
            upscalerModel = picture.upscalerModel,
            upscalerTta = picture.upscalerTta,
            deblock = picture.deblock,
            dering = picture.dering,
            shader = picture.shader,
            temporalDenoise = picture.temporalDenoise,
            dotCrawl = picture.dotCrawl,
            colorize = picture.colorize.name,
            colourSystem = picture.colourSystem.name,
        )

        private fun chromaOf(name: String) =
            runCatching { PictureAdjustment.Chroma.valueOf(name) }.getOrDefault(PictureAdjustment.Chroma.BILINEAR)

        private fun colorizeOf(name: String) =
            runCatching { PictureAdjustment.Colorize.valueOf(name) }.getOrDefault(PictureAdjustment.Colorize.OFF)

        private fun interpolationOf(name: String) = runCatching { PictureAdjustment.Interpolation.valueOf(name) }
            .getOrDefault(PictureAdjustment.Interpolation.OFF)

        private fun upscalerOf(name: String) =
            runCatching { PictureAdjustment.Upscaler.valueOf(name) }.getOrDefault(PictureAdjustment.Upscaler.STANDARD)

        private fun deinterlaceOf(name: String) = runCatching { PictureAdjustment.Deinterlace.valueOf(name) }
            .getOrDefault(PictureAdjustment.Deinterlace.OFF)
    }
}

/** The key of the device's own screen, the one an output screen's setup is kept under when it is the only one. */
const val BUILT_IN_SCREEN = "built-in"

/** The id the media folder chosen at the start keeps as a source. */
const val PRIMARY_SOURCE = "primary"
