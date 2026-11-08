package com.hawkynt.mediadroyd.engine

/**
 * What to play: the video at [path] (a local path or a source's, see SourceAccess), the subtitle and audio files
 * beside it ([subtitles], [audio]: Sidecars), where to start ([startMs]) and the household's preferred audio
 * language ([audioLanguage], empty for the file's own default).
 */
data class MediaRequest(
    val path: String,
    val subtitles: List<SubtitleFile> = emptyList(),
    val audio: List<String> = emptyList(),
    val startMs: Long = 0,
    val audioLanguage: String = "",
) {
    /**
     * A subtitle file beside the video: its [path], [mimeType] (null: told from the file), [language], [label] and
     * whether it is [forced].
     */
    data class SubtitleFile(
        val path: String,
        val mimeType: String?,
        val language: String?,
        val label: String,
        val forced: Boolean,
    )
}
