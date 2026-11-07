package com.hawkynt.mediadroid.player

/**
 * The mpv user shaders the household put in its shader folder (on Android a folder picked in Settings, on the desktop
 * `shaders/` in the app-data folder): every `.glsl` or `.hook` file there (mpv takes either; its prescalers come as
 * `.hook`) read as an [MpvShader], by its file name — what
 * [PictureAdjustment.shader] names and the stages run. Set by the app when it reads the folder, read by the stages on
 * their own threads.
 */
object MpvShaders {
    @Volatile
    var library: Map<String, MpvShader> = emptyMap()
        private set

    /** [files] (file name to text) taken as the library: the shader files ([isShader]), read, by name. */
    fun load(files: Map<String, String>) {
        library = files.filterKeys(::isShader)
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .mapValues { (name, text) -> MpvShader.parse(text, label(name)) }
    }

    /** Whether the file [name] is an mpv user shader: `.glsl` or `.hook`. */
    fun isShader(name: String): Boolean = EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    /** The file [name] as a choice is labelled: without its extension. */
    fun label(name: String): String =
        EXTENSIONS.firstOrNull { name.endsWith(it, ignoreCase = true) }?.let { name.dropLast(it.length) } ?: name

    private val EXTENSIONS = listOf(".glsl", ".hook")

    /**
     * Whether the stage runs compute shaders (OpenGL ES 3.1, desktop OpenGL 4.3), which a shader with compute passes
     * needs: set by the stage once it knows its graphics.
     */
    @Volatile
    var compute: Boolean = false

    /** The names a picture may choose: the shaders the stages can run. */
    val choices: List<String> get() = library.filterValues { runnable(it) }.keys.toList()

    /**
     * Whether the stage can run [shader]: it is [MpvShader.supported], and has compute where it needs it — for its
     * compute passes or its storage buffers.
     */
    fun runnable(shader: MpvShader): Boolean =
        shader.supported && (compute || !shader.usesCompute && !shader.usesStorage)
}
