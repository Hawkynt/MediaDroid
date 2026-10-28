package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.FsFile

/** One network source's files, over one connection kept until [close]. */
interface RemoteFs : AutoCloseable {
    /** The source's root folder. */
    val root: FsFile

    /** The entry at [location], or null when it lies outside this source or cannot be found. */
    fun resolve(location: String): FsFile?
}
