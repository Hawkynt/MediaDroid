package com.hawkynt.mediadroyd.player

/**
 * The fonts a Matroska file carries for its subtitles: its attachments ([AssFonts] tells which are fonts), read with as
 * few reads as the file allows — the segment's top-level elements walked up to the first cluster, the attachments
 * reached through the seek head where they come after the clusters, and only the fonts' own bytes read whole. Media3
 * leaves attachments out; libass needs them to draw a script as it was made.
 *
 * The element IDs are Matroska's (RFC 9559): EBML 0x1A45DFA3, Segment 0x18538067, SeekHead 0x114D9B74 with Seek
 * 0x4DBB, SeekID 0x53AB and SeekPosition 0x53AC (from the segment's data), Cluster 0x1F43B675, Attachments 0x1941A469
 * with AttachedFile 0x61A7, FileName 0x466E, FileMediaType 0x4660 and FileData 0x465C.
 */
object MatroskaFonts {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CLUSTER = 0x1F43B675L
    private const val ATTACHMENTS = 0x1941A469L
    private const val ATTACHED_FILE = 0x61A7L
    private const val FILE_NAME = 0x466EL
    private const val FILE_MEDIA_TYPE = 0x4660L
    private const val FILE_DATA = 0x465CL

    /** The most a font may weigh: CJK collections run to a few tens of megabytes. */
    private const val LARGEST_FONT = 128L shl 20

    /** The most a seek head or an attached file's own fields may weigh. */
    private const val LARGEST_FIELDS = 1L shl 20

    /** An element: its [id] (marker bits kept, as the IDs are written), where its data starts and how long it is. */
    private class Element(val id: Long, val data: Long, val size: Long) {
        val end: Long get() = data + size
    }

    /**
     * The fonts in the Matroska file of [length] bytes that [read] reads ([read] (position, count) giving up to count
     * bytes from position), by file name; empty where it is no Matroska file, has none, or is cut short.
     */
    fun read(length: Long, read: (Long, Int) -> ByteArray): List<Pair<String, ByteArray>> =
        runCatching { Reader(length, read).fonts() }.getOrDefault(emptyList())

    private class Reader(private val length: Long, private val source: (Long, Int) -> ByteArray) {

        private fun bytes(at: Long, count: Int): ByteArray {
            require(at >= 0 && at + count <= length) { "past the end" }
            val got = source(at, count)
            require(got.size >= count) { "cut short" }
            return got
        }

        /** The element whose header starts at [at]; its size -1 where it is unknown (written while streaming). */
        fun element(at: Long): Element {
            val head = bytes(at, minOf(12L, length - at).toInt())
            val idLength = vintLength(head[0])
            require(idLength <= 4)
            var id = 0L
            for (i in 0 until idLength) id = (id shl 8) or (head[i].toLong() and 0xFF)
            val sizeLength = vintLength(head[idLength])
            var size = head[idLength].toLong() and (0xFFL shr sizeLength)
            var unknown = size == (0xFFL shr sizeLength)
            for (i in 1 until sizeLength) {
                val b = head[idLength + i].toLong() and 0xFF
                size = (size shl 8) or b
                unknown = unknown && b == 0xFFL
            }
            return Element(id, at + idLength + sizeLength, if (unknown) -1 else size)
        }

        private fun vintLength(first: Byte): Int {
            val b = first.toInt() and 0xFF
            require(b != 0) { "not a vint" }
            return Integer.numberOfLeadingZeros(b) - 23
        }

        /** The children of [parent] (of known size). */
        private fun children(parent: Element): List<Element> {
            val found = mutableListOf<Element>()
            var at = parent.data
            while (at < parent.end) {
                val child = element(at)
                require(child.size >= 0 && child.end <= parent.end)
                found += child
                at = child.end
            }
            return found
        }

        private fun number(element: Element): Long {
            require(element.size in 1..8)
            return bytes(element.data, element.size.toInt()).fold(0L) { n, b -> (n shl 8) or (b.toLong() and 0xFF) }
        }

        private fun text(element: Element): String {
            require(element.size in 0..LARGEST_FIELDS)
            return String(bytes(element.data, element.size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
        }

        fun fonts(): List<Pair<String, ByteArray>> {
            val ebml = element(0)
            require(ebml.id == EBML && ebml.size >= 0)
            val segment = element(ebml.end)
            require(segment.id == SEGMENT)
            val segmentEnd = if (segment.size < 0) length else minOf(length, segment.end)
            val seekHeads = mutableSetOf<Long>()
            val sought = mutableListOf<Long>()
            var at = segment.data
            // The top level up to the first cluster: what most muxers put before the pictures.
            while (at < segmentEnd) {
                val child = element(at)
                when (child.id) {
                    ATTACHMENTS -> return attachments(child)
                    SEEK_HEAD -> seek(child, segment.data, seekHeads, sought)
                    CLUSTER -> break
                }
                if (child.size < 0) break
                at = child.end
            }
            // Past the clusters only where the seek head says where.
            for (position in sought) {
                val child = element(position)
                if (child.id == ATTACHMENTS) return attachments(child)
            }
            return emptyList()
        }

        /** The attachments' positions in the seek head [head] into [sought], following a second seek head once. */
        private fun seek(head: Element, segment: Long, seekHeads: MutableSet<Long>, sought: MutableList<Long>) {
            if (!seekHeads.add(head.data) || head.size !in 0..LARGEST_FIELDS) return
            for (entry in children(head).filter { it.id == SEEK }) {
                val fields = children(entry)
                val id = fields.firstOrNull { it.id == SEEK_ID }?.let(::number) ?: continue
                val position = fields.firstOrNull { it.id == SEEK_POSITION }?.let(::number) ?: continue
                when (id) {
                    ATTACHMENTS -> sought += segment + position
                    SEEK_HEAD -> runCatching {
                        element(segment + position).takeIf { it.id == SEEK_HEAD }
                            ?.let { seek(it, segment, seekHeads, sought) }
                    }
                }
            }
        }

        private fun attachments(attachments: Element): List<Pair<String, ByteArray>> {
            require(attachments.size >= 0)
            val fonts = mutableListOf<Pair<String, ByteArray>>()
            var at = attachments.data
            while (at < attachments.end) {
                val file = element(at)
                require(file.size >= 0 && file.end <= attachments.end)
                at = file.end
                if (file.id != ATTACHED_FILE) continue
                var name = ""
                var type: String? = null
                var data: Element? = null
                var field = file.data
                while (field < file.end) {
                    val child = element(field)
                    require(child.size >= 0 && child.end <= file.end)
                    when (child.id) {
                        FILE_NAME -> name = text(child)
                        FILE_MEDIA_TYPE -> type = text(child)
                        FILE_DATA -> data = child
                    }
                    field = child.end
                }
                val content = data ?: continue
                if (!AssFonts.isFont(type, name) || content.size > LARGEST_FONT) continue
                fonts += name to bytes(content.data, content.size.toInt())
            }
            return fonts
        }
    }
}
