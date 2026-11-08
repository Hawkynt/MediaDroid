package com.hawkynt.mediadroyd.sources

/**
 * Where a source's password or key comes from — on the device, the Android Keystore. Asked for by
 * [com.hawkynt.mediadroyd.library.Source.id]; null when the source has none (a guest share).
 */
fun interface Secrets {
    fun secretFor(sourceId: String): String?
}
