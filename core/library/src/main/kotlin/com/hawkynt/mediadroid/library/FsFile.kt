package com.hawkynt.mediadroid.library

import java.io.File
import java.io.InputStream

/**
 * A file or folder as the scanner and the Files browser see it — on the device, or on a network or
 * cloud [Source]. It offers the little of `java.io.File` they use, so every source looks the same to
 * them; reading goes through [open], so nothing assumes a local path.
 */
interface FsFile {
    val name: String

    /**
     * Where it is: an absolute path on the device, a URI elsewhere (`smb://nas/media/Heat.mkv`). It is
     * the key resume points and watched marks are stored under.
     */
    val path: String
    val isDirectory: Boolean
    val isFile: Boolean get() = !isDirectory

    /** The folder it is in; null at a source's root. */
    val parentFile: FsFile?

    /** Its size in bytes; 0 for a folder or when unknown. */
    fun length(): Long

    /** When it last changed, in epoch milliseconds; 0 when unknown. */
    fun lastModified(): Long

    /** What a folder holds; null when it cannot be read. */
    fun listFiles(): List<FsFile>?

    /** The entry called [name] in this folder, whether or not it exists. */
    fun child(name: String): FsFile

    /** Reads it from byte [offset] on — 0 is the start; playback seeks by opening further in. */
    fun open(offset: Long = 0): InputStream
}

/** The name without its last extension, as `java.io.File` has it. */
val FsFile.nameWithoutExtension: String get() = name.substringBeforeLast('.')

/** The last extension, without the dot; empty when there is none. */
val FsFile.extension: String get() = name.substringAfterLast('.', "")

/** The whole content as UTF-8 text, or null when it cannot be read — for NFOs, EDLs and the like. */
fun FsFile.readTextOrNull(): String? = runCatching { open().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

/** A file or folder on the device. */
class LocalFile(val file: File) : FsFile {
    override val name: String get() = file.name
    override val path: String get() = file.absolutePath
    override val isDirectory: Boolean get() = file.isDirectory
    override val parentFile: FsFile? get() = file.parentFile?.let(::LocalFile)

    override fun length(): Long = file.length()

    override fun lastModified(): Long = file.lastModified()

    override fun listFiles(): List<FsFile>? = file.listFiles()?.map(::LocalFile)

    override fun child(name: String): FsFile = LocalFile(File(file, name))

    override fun open(offset: Long): InputStream =
        file.inputStream().also { if (offset > 0) it.channel.position(offset) }

    override fun equals(other: Any?): Boolean = other is LocalFile && other.file == file

    override fun hashCode(): Int = file.hashCode()

    override fun toString(): String = path
}
