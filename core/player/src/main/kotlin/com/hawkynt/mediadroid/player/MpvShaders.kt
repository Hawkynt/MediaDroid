package com.hawkynt.mediadroid.player

/**
 * The mpv user shaders the household put in its shader folder (on Android a folder picked in Settings, on the desktop
 * `shaders/` in the app-data folder): every `.glsl` file there read as an [MpvShader], by its file name — what
 * [PictureAdjustment.shader] names and the stages run. Set by the app when it reads the folder, read by the stages on
 * their own threads.
 */
object MpvShaders {
    @Volatile
    var library: Map<String, MpvShader> = emptyMap()
        private set

    /** [files] (file name to text) taken as the library: the `.glsl` ones, read, by name. */
    fun load(files: Map<String, String>) {
        library = files.filterKeys { it.endsWith(".glsl", ignoreCase = true) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .mapValues { (name, text) -> MpvShader.parse(text, name.removeSuffix(".glsl")) }
    }

    /** The names a picture may choose: the shaders the stages can run. */
    val choices: List<String> get() = library.filterValues { it.supported }.keys.toList()
}
