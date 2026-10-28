package com.hawkynt.mediadroid.sources.integration

import com.hawkynt.mediadroid.library.LibraryScanner
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.library.readTextOrNull
import com.hawkynt.mediadroid.sources.NfsFs
import java.io.File
import java.io.RandomAccessFile
import java.io.StringReader
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.security.auth.Subject
import org.dcache.nfs.ExportFile
import org.dcache.nfs.status.NoEntException
import org.dcache.nfs.status.RoFsException
import org.dcache.nfs.v3.MountServer
import org.dcache.nfs.v3.NfsServerV3
import org.dcache.nfs.v4.NfsIdMapping
import org.dcache.nfs.v4.SimpleIdMap
import org.dcache.nfs.v4.xdr.nfsace4
import org.dcache.nfs.vfs.AclCheckable
import org.dcache.nfs.vfs.DirectoryEntry
import org.dcache.nfs.vfs.DirectoryStream
import org.dcache.nfs.vfs.FsStat
import org.dcache.nfs.vfs.Inode
import org.dcache.nfs.vfs.Stat
import org.dcache.nfs.vfs.VirtualFileSystem
import org.dcache.oncrpc4j.rpc.OncRpcProgram
import org.dcache.oncrpc4j.rpc.OncRpcSvc
import org.dcache.oncrpc4j.rpc.OncRpcSvcBuilder
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * NfsFs against dCache's nfs4j — an independent NFSv3 and MOUNT server, run in the test over a
 * folder of files — on one port without a portmapper, as a userspace server is set up.
 */
class NfsIntegrationTest {

    private fun source() = Source("nfs", "NAS", SourceKind.NFS, host = "127.0.0.1", port = port, path = "/")

    @Test
    fun anExportListsReadsAndScans() {
        NfsFs(source()).use { fs ->
            val movies = fs.root.listFiles()!!.single { it.name == "Movies" }
            assertTrue(movies.isDirectory)
            val video = movies.listFiles()!!.single { it.name == "Heat (1995).mkv" }
            assertEquals(BIG.size.toLong(), video.length())
            assertEquals(MODIFIED, video.lastModified())
            assertArrayEquals(BIG, video.open().use { it.readBytes() })
            // Across the 64 KB reads the client asks for.
            assertArrayEquals(BIG.copyOfRange(70_000, BIG.size), video.open(70_000).use { it.readBytes() })
            assertEquals("<movie><title>Heat</title></movie>", movies.child("movie.nfo").readTextOrNull())

            val found = fs.resolve("nfs://127.0.0.1:$port/Movies/Heat (1995).mkv")!!
            assertEquals(BIG.size.toLong(), found.length())
            assertNull(fs.resolve("nfs://127.0.0.1:$port/Movies/missing.mkv"))
            assertNull(fs.resolve("smb://127.0.0.1/other"))
            assertEquals("Heat", LibraryScanner.scan(fs.root).movies.single().title)
        }
    }

    companion object {
        private val BIG = ByteArray(200_000) { (it % 251).toByte() }
        private const val MODIFIED = 1_546_423_460_000L
        private lateinit var dir: File
        private lateinit var server: OncRpcSvc
        private var port = 0

        @BeforeClass
        @JvmStatic
        fun startNfs() {
            dir = Files.createTempDirectory("mdnfs").toFile()
            File(dir, "Movies").mkdirs()
            File(dir, "Movies/Heat (1995).mkv").apply { writeBytes(BIG) }.setLastModified(MODIFIED)
            File(dir, "Movies/movie.nfo").writeText("<movie><title>Heat</title></movie>")
            port = ServerSocket(0).use { it.localPort }
            val exports = ExportFile(StringReader("/ *(ro,insecure,no_root_squash)\n"))
            val vfs = FolderFs(dir)
            server = OncRpcSvcBuilder()
                .withPort(port)
                .withTCP()
                .withoutAutoPublish()
                .withWorkerThreadIoStrategy()
                .withBindAddress("127.0.0.1")
                .withRpcService(OncRpcProgram(100003, 3), NfsServerV3(exports, vfs))
                .withRpcService(OncRpcProgram(100005, 3), MountServer(exports, vfs))
                .build()
            server.start()
        }

        @AfterClass
        @JvmStatic
        fun stopNfs() {
            server.stop()
            dir.deleteRecursively()
        }
    }

    /** A read-only folder on disk as nfs4j's file system: inodes are numbered as files are met. */
    private class FolderFs(root: File) : VirtualFileSystem {
        private val files = mutableListOf(root)

        private fun inodeOf(file: File): Inode {
            var index = files.indexOf(file)
            if (index < 0) {
                files += file
                index = files.lastIndex
            }
            return Inode.forFile(ByteBuffer.allocate(8).putLong(index + 1L).array())
        }

        private fun fileOf(inode: Inode): File = files[(ByteBuffer.wrap(inode.fileId).long - 1).toInt()]

        private fun stat(file: File): Stat = Stat().apply {
            setDev(1)
            setIno(files.indexOf(file).toLong() + 1)
            setMode(if (file.isDirectory) Stat.S_IFDIR or 0b111_101_101 else Stat.S_IFREG or 0b110_100_100)
            setNlink(1)
            setUid(0)
            setGid(0)
            setRdev(0)
            setSize(if (file.isDirectory) 4096 else file.length())
            setATime(file.lastModified())
            setMTime(file.lastModified())
            setCTime(file.lastModified())
            setGeneration(0)
        }

        override fun getRootInode(): Inode = inodeOf(files[0])

        override fun lookup(parent: Inode, name: String): Inode {
            val child = File(fileOf(parent), name)
            if (!child.exists()) throw NoEntException(name)
            return inodeOf(child)
        }

        override fun getattr(inode: Inode): Stat = stat(fileOf(inode))

        override fun list(inode: Inode, verifier: ByteArray, cookie: Long): DirectoryStream {
            val children = fileOf(inode).listFiles()!!.sortedBy { it.name }
            val entries = children.mapIndexed { i, child ->
                DirectoryEntry(child.name, inodeOf(child), stat(child), i + 3L)
            }
            return DirectoryStream(directoryVerifier(inode), entries).tail(cookie)
        }

        override fun directoryVerifier(inode: Inode): ByteArray = ByteArray(8)

        override fun read(inode: Inode, data: ByteArray, offset: Long, count: Int): Int =
            RandomAccessFile(fileOf(inode), "r").use { file ->
                file.seek(offset)
                file.read(data, 0, count).coerceAtLeast(0)
            }

        override fun parentOf(inode: Inode): Inode = inodeOf(fileOf(inode).parentFile ?: files[0])

        override fun access(subject: Subject, inode: Inode, mode: Int): Int = mode

        override fun getFsStat(): FsStat = FsStat(1L shl 40, 1000, 0, files.size.toLong())

        override fun getAclCheckable(): AclCheckable = AclCheckable.UNDEFINED_ALL

        override fun getIdMapper(): NfsIdMapping = SimpleIdMap()

        override fun getAcl(inode: Inode): Array<nfsace4> = emptyArray()

        override fun hasIOLayout(inode: Inode): Boolean = false

        override fun getCaseInsensitive(): Boolean = false

        override fun getCasePreserving(): Boolean = true

        override fun readlink(inode: Inode): String = throw RoFsException("read-only")

        override fun create(parent: Inode, type: Stat.Type, name: String, subject: Subject, mode: Int): Inode =
            throw RoFsException("read-only")

        override fun link(parent: Inode, link: Inode, name: String, subject: Subject): Inode =
            throw RoFsException("read-only")

        override fun mkdir(parent: Inode, name: String, subject: Subject, mode: Int): Inode =
            throw RoFsException("read-only")

        override fun move(src: Inode, oldName: String, dest: Inode, newName: String): Boolean =
            throw RoFsException("read-only")

        override fun remove(parent: Inode, name: String) = throw RoFsException("read-only")

        override fun symlink(parent: Inode, name: String, link: String, subject: Subject, mode: Int): Inode =
            throw RoFsException("read-only")

        override fun write(
            inode: Inode,
            data: ByteArray,
            offset: Long,
            count: Int,
            stabilityLevel: VirtualFileSystem.StabilityLevel,
        ): VirtualFileSystem.WriteResult = throw RoFsException("read-only")

        override fun commit(inode: Inode, offset: Long, count: Int) = Unit

        override fun setattr(inode: Inode, stat: Stat) = throw RoFsException("read-only")

        override fun setAcl(inode: Inode, acl: Array<nfsace4>) = throw RoFsException("read-only")
    }
}
