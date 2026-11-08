package com.hawkynt.mediadroyd.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hawkynt.mediadroyd.BuildConfig
import com.hawkynt.mediadroyd.data.LibraryStore
import com.hawkynt.mediadroyd.data.SourceAccess
import com.hawkynt.mediadroyd.state.StateStore
import com.hawkynt.mediadroyd.ui.platform.StorageRoot
import com.hawkynt.mediadroyd.data.StorageRoots
import com.hawkynt.mediadroyd.library.Library
import com.hawkynt.mediadroyd.library.LibraryScanner
import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.library.SourceKind
import com.hawkynt.mediadroyd.library.StorageVolumes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Authorities whose tree document ids [StorageVolumes] can turn back into file paths. */
private val LocalStorageAuthorities = setOf(
    "com.android.externalstorage.documents",
    "com.android.providers.downloads.documents",
)

/**
 * The app shell. There is no library database: the folders are re-read whenever the app starts or
 * comes back to the foreground, and the previous result stays on screen until the new one is in,
 * so a rescan never blanks the wall. Watched marks and resume positions live in [StateStore].
 *
 * [demo] is the documentation mode the README screenshots are taken in: the library is the generated
 * demo folder in [StateStore.data]'s root, file access counts as granted and the player does not play
 * its placeholder videos.
 */
@Composable
fun MediaDroydApp(store: StateStore, demo: Boolean = false) {
    // The shared screens' pictures and facts, and the sources' keeping, the Android way.
    val context = LocalContext.current
    val art = remember { com.hawkynt.mediadroyd.data.AndroidMediaArt(context) }
    val keeper = remember { com.hawkynt.mediadroyd.data.AndroidSourceKeeper(context) }
    val files = remember { com.hawkynt.mediadroyd.data.AndroidMediaFiles(context) }
    val settings = remember { AndroidSettingsPlatform(context, store) }
    CompositionLocalProvider(
        com.hawkynt.mediadroyd.ui.platform.LocalMediaArt provides art,
        com.hawkynt.mediadroyd.ui.platform.LocalSourceKeeper provides keeper,
        com.hawkynt.mediadroyd.ui.platform.LocalMediaFiles provides files,
        com.hawkynt.mediadroyd.ui.platform.LocalSettingsPlatform provides settings,
    ) {
        AppShell(store, demo)
    }
}

@Composable
private fun AppShell(store: StateStore, demo: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Stored locations on network sources resolve against the saved sources, for player and pictures.
    remember(store) {
        val app = context.applicationContext
        SourceAccess.sources = { store.sources }
        com.hawkynt.mediadroyd.player.AviAudioFraming.remote = { SourceAccess.resolve(app, it) }
        com.hawkynt.mediadroyd.player.ownedLocation = { Source.owning(store.sources, it) != null }
    }
    // The mpv user shaders of the folder picked in Settings, read once off the main thread (MpvShaderFolder).
    LaunchedEffect(store) {
        val folder = store.data.settings.shaderFolder
        if (!demo && folder.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.hawkynt.mediadroyd.data.MpvShaderFolder.load(context.applicationContext, folder)
            }
        }
    }

    // All-files access, where the build asks for it (BuildConfig.ALL_FILES); the Play build reads picked folders only.
    fun storageAccess() = demo || !BuildConfig.ALL_FILES || Environment.isExternalStorageManager()
    var hasAccess by remember { mutableStateOf(storageAccess()) }
    var rootPath by remember { mutableStateOf(store.data.rootPath) }
    var library by remember { mutableStateOf<Library?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // Sources that could not be read on the last scan, by id: their titles are missing until they are back.
    var unreachable by remember { mutableStateOf(emptySet<String>()) }
    val libraries = remember { LibraryStore(context) }
    var scanGeneration by remember { mutableIntStateOf(0) }
    var choosingFolder by remember { mutableStateOf(false) }
    // Where the app is (the shared shell's state), kept across a folder chosen anew.
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val shell = rememberShellState(store, wide = wide)
    // Scroll positions outlive a visit to a title's details and the first start, so Back lands where you were.
    val grids = ShellGrids(
        rememberLazyGridState(), rememberLazyGridState(), rememberLazyGridState(), rememberLazyGridState(),
    )
    // What plays now, apart from the screen shown (#417): full screen over it, or — after Back — small in the
    // sidebar's corner, playing on (on a second screen too) while the menus are used; where that corner lies.
    var playing by remember { mutableStateOf<Screen.Player?>(null) }
    var playingSmall by remember { mutableStateOf(false) }
    var playingCorner by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    fun startPlaying(player: Screen.Player) {
        playing = player
        playingSmall = false
    }
    // The demo has no real volumes: its library stands in for the internal storage. Without all-files access (the
    // Play build) the device's storage is not readable by path: the picked folders, sources, stand in for it.
    val volumes = remember(demo) {
        when {
            demo -> listOf(StorageRoot("Internal storage", store.data.rootPath, removable = false))
            !BuildConfig.ALL_FILES -> emptyList()
            else -> StorageRoots.list(context)
        }
    }
    // The Files browser starts from the device's volumes and every saved network or cloud source.
    val storageRoots = volumes + store.sources.filter { it.kind != SourceKind.LOCAL }
        .map { StorageRoot(it.name, it.rootUri, removable = false) }
    fun scan(path: String, adopt: Boolean = false) {
        val generation = ++scanGeneration
        scanError = null
        scanning = true
        scope.launch {
            // The chosen folder first, with every other source's last scan, so the walls show at once.
            // A picked folder (the Play build's main folder) is read through its document tree, a path directly.
            val picked = store.sources.firstOrNull { it.kind == SourceKind.DOCUMENTS && it.url == path }
            val local = withContext(Dispatchers.IO) {
                runCatching {
                    if (picked != null) {
                        val root = requireNotNull(SourceAccess.of(context).root(picked)) { "the folder is not there" }
                        LibraryScanner.scan(root)
                    } else {
                        val root = File(path)
                        require(root.isDirectory) {
                            "the folder is not there — is the SD card or USB drive plugged in?"
                        }
                        LibraryScanner.scan(root)
                    }
                }
            }
            if (generation != scanGeneration) return@launch
            val others = store.sources.filter { (it.kind != SourceKind.LOCAL || it.path != path) && it != picked }
            local.onFailure {
                scanning = false
                scanError = "Could not read $path: ${it.message}"
                return@launch
            }
            val main = local.getOrThrow()
            val cached = withContext(Dispatchers.IO) { others.mapNotNull { libraries.load(it.id) } }
            library = Library.merge(listOf(main) + cached)
            if (adopt) {
                rootPath = path
                store.data.rootPath = path
                store.save()
                choosingFolder = false
                shell.home()
            }
            // Then every other source live: one that answers is scanned and kept, one that does not
            // stays on the walls with its last scan, marked as not reachable.
            val (scanned, away) = withContext(Dispatchers.IO) {
                val files = SourceAccess.of(context)
                val scans = others.associate { source ->
                    source.id to runCatching {
                        val root = files.root(source) ?: return@runCatching null
                        val sidecars = libraries.sidecars(source.id)
                        LibraryScanner.scan(sidecars.wrap(root)).also {
                            libraries.save(source.id, it)
                            libraries.saveSidecars(source.id, sidecars)
                        }
                    }.getOrNull()
                }
                val away = scans.filterValues { it == null }.keys
                (scans.values.filterNotNull() + away.mapNotNull { libraries.load(it) }) to away
            }
            if (generation != scanGeneration) return@launch
            scanning = false
            library = Library.merge(listOf(main) + scanned)
            unreachable = away
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = storageAccess()
                // Back in the foreground: re-read the folders, they are the only truth.
                if (hasAccess && rootPath.isNotEmpty() && !choosingFolder) scan(rootPath)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            // Backed out of the picker: with a library already on screen, simply go back to it.
            if (library != null) choosingFolder = false
            return@rememberLauncherForActivityResult
        }
        if (!BuildConfig.ALL_FILES) {
            // Read through the picked tree, its permission kept across restarts; kept as a source so its videos
            // resolve like a cloud folder's.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val url = uri.toString()
            if (store.sources.none { it.url == url }) {
                store.saveSources(
                    store.sources + Source(
                        java.util.UUID.randomUUID().toString(),
                        cloudFolderName(context, uri),
                        SourceKind.DOCUMENTS,
                        host = uri.authority.orEmpty(),
                        url = url,
                    ),
                )
            }
            scan(url, adopt = true)
            return@rememberLauncherForActivityResult
        }
        val path = if (uri.authority in LocalStorageAuthorities) {
            StorageVolumes.pathForTreeDocumentId(
                DocumentsContract.getTreeDocumentId(uri),
                Environment.getExternalStorageDirectory().absolutePath,
            )
        } else {
            null
        }
        if (path == null) {
            scanError = "That folder is not on this device's storage. " +
                "Pick one on the internal storage, an SD card or a USB drive."
        } else {
            scan(path, adopt = true)
        }
    }

    // A further folder on the device, kept as a source of its own beside the media folder; without all-files access
    // (the Play build) every one is read through its document tree, as a cloud folder is.
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val path = if (BuildConfig.ALL_FILES && uri.authority in LocalStorageAuthorities) {
            StorageVolumes.pathForTreeDocumentId(
                DocumentsContract.getTreeDocumentId(uri),
                Environment.getExternalStorageDirectory().absolutePath,
            )
        } else {
            null
        }
        val added = when {
            path != null && store.sources.none { it.kind == SourceKind.LOCAL && it.path == path } ->
                Source.local(java.util.UUID.randomUUID().toString(), File(path).name.ifEmpty { path }, path)
            path == null && store.sources.none { it.url == uri.toString() } -> {
                // A cloud folder through its document provider: keep the permission across restarts.
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Source(
                    java.util.UUID.randomUUID().toString(),
                    cloudFolderName(context, uri),
                    SourceKind.DOCUMENTS,
                    host = uri.authority.orEmpty(),
                    url = uri.toString(),
                )
            }
            else -> null
        }
        if (added != null) {
            store.saveSources(store.sources + added)
            scan(rootPath)
        }
    }

    fun launchPicker() {
        choosingFolder = true
        try {
            pickFolder.launch(null)
        } catch (_: ActivityNotFoundException) {
            scanError = "This device has no folder picker. Type the folder's path instead."
        }
    }

    val needsSetup = !hasAccess || rootPath.isEmpty() || choosingFolder || (library == null && scanError != null)
    if (needsSetup) {
        OnboardingScreen(
            hasAccess = hasAccess,
            currentPath = rootPath,
            scanning = scanning,
            scanError = scanError,
            canCancel = library != null && hasAccess,
            onGrantAccess = {
                val intent = runCatching {
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                }.getOrElse { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
                context.startActivity(intent)
            },
            onPickFolder = ::launchPicker,
            onUsePath = { scan(it.trim(), adopt = true) },
            canTypePath = BuildConfig.ALL_FILES,
            onCancel = {
                choosingFolder = false
                scanError = null
            },
        )
        return
    }

    val lib = library
    if (lib == null) {
        LoadingScreen()
        // First start with a saved folder: kick off the scan once, the resume observer may have
        // run before access was known.
        LaunchedEffect(Unit) { if (!scanning) scan(rootPath) }
        return
    }

    // The screen shown, and over it what plays (see playing): one place in the tree for the player, so Back only
    // shrinks it — nothing about its playing, its second screen or its sound starts over.
    Box(Modifier.fillMaxSize()) {
        val details = shell.screen as? Screen.Details
        LibraryShell(
            state = shell,
            library = lib,
            store = store,
            scanning = scanning,
            rootPath = rootPath,
            roots = storageRoots,
            onRefresh = { scan(rootPath) },
            onPlay = ::startPlaying,
            onChangeFolder = ::launchPicker,
            onAddDeviceFolder = { addFolder.launch(null) },
            unreachable = unreachable,
            playingCorner = if (playing != null && playingSmall) {
                { rect -> playingCorner = rect }
            } else {
                null
            },
            grids = grids,
        )
        playing?.let { player ->
            val density = androidx.compose.ui.platform.LocalDensity.current
            val corner = playingCorner.takeIf { details == null }
            val place = when {
                !playingSmall -> Modifier.fillMaxSize()
                corner != null -> with(density) {
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(corner.left.toInt(), corner.top.toInt()) }
                        .size(corner.width.toDp(), corner.height.toDp())
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                }
                // No sidebar on a title's details: the lower left corner.
                else -> Modifier
                    .align(Alignment.BottomStart)
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .size(192.dp, 108.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            }
            key(player) {
                PhonePlayer(
                    queue = player.queue,
                    startIndex = player.startIndex,
                    store = store,
                    autoAdvance = player.autoAdvance,
                    onExit = {
                        playing = null
                        playingSmall = false
                    },
                    demo = demo,
                    modifier = place,
                    small = playingSmall,
                    onShrink = { playingSmall = true },
                    onGrow = { playingSmall = false },
                )
            }
        }
    }
}

/** A cloud folder's name as its provider shows it, else the provider's own name. */
private fun cloudFolderName(context: android.content.Context, tree: Uri): String = runCatching {
    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    context.contentResolver.query(
        document,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull() ?: tree.authority.orEmpty()
