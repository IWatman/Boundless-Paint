package app.painter.core.io

import app.painter.core.adjust.AdjustCodec
import app.painter.core.adjust.UnknownAdjust
import app.painter.core.blend.BlendMode
import app.painter.core.color.Rgba
import app.painter.core.doc.AdjustmentLayer
import app.painter.core.doc.ColorTag
import app.painter.core.doc.Document
import app.painter.core.doc.EditTarget
import app.painter.core.doc.GroupNode
import app.painter.core.doc.LayerMask
import app.painter.core.doc.Node
import app.painter.core.doc.ProjectType
import app.painter.core.doc.RasterLayer
import app.painter.core.raster.MaskGrid
import app.painter.core.raster.MaskTile
import app.painter.core.raster.Tile
import app.painter.core.raster.TileGrid
import app.painter.core.raster.TileKey
import net.jpountz.lz4.LZ4FrameInputStream
import net.jpountz.lz4.LZ4FrameOutputStream
import kotlinx.serialization.json.JsonObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Reads and writes projects in the unpacked directory layout of spec/file-formats.md («Project .pnt»)
 * and packs/unpacks them as `.pnt` ZIP archives.
 */
object ProjectIO {
    internal const val MANIFEST = "manifest.json"
    internal const val THUMB = "thumb.png"
    internal const val LAYERS = "layers"
    internal const val MASKS = "masks"
    internal const val SELECTION = "selection"
    internal const val TILES_IDX = "tiles.idx"
    internal const val TILES_BIN = "tiles.bin"

    private const val INDEX_ENTRY_BYTES = 12
    private const val TILE_FLOATS = Tile.SIZE * Tile.SIZE * 4
    private const val TILE_RAW_BYTES = TILE_FLOATS * 2
    private const val MASK_RAW_BYTES = Tile.SIZE * Tile.SIZE
    private val OWNED = setOf(MANIFEST, THUMB, LAYERS, MASKS, SELECTION, FsUtil.COMPLETE_MARKER)

    /** Receives non-fatal load warnings (e.g. skipped unknown node kinds). */
    @Volatile
    var warn: (String) -> Unit = { System.err.println("ProjectIO: " + it) }

    /**
     * Atomically saves [doc] into [dir] (manifest schema 2): everything is written into a sibling `<dir>.tmp`
     * which then replaces [dir]. Empty tiles, and mask tiles equal to the mask default, are skipped. Entries of an existing project
     * that this writer does not own (refs, history, ...) are carried over.
     */
    fun save(doc: Document, dir: File, thumbnailPng: ByteArray?) {
        recover(dir)
        val tmp = FsUtil.tmpOf(dir)
        FsUtil.deleteRecursively(tmp)
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")

        val layersDir = File(tmp, LAYERS)
        layersDir.mkdirs()
        val masksDir = File(tmp, MASKS)
        doc.root.walk { node ->
            if (node is RasterLayer) writeTiles(node.pixels, File(layersDir, safeName(node.id)))
            node.mask?.let { writeMaskTiles(it.tiles, File(masksDir, safeName(node.id))) }
        }
        if (!doc.selection.isEmpty) writeMaskTiles(doc.selection.mask, File(tmp, SELECTION))
        if (thumbnailPng != null) File(tmp, THUMB).writeBytes(thumbnailPng)
        File(tmp, MANIFEST).writeText(manifestJson.encodeToString(ManifestDto.serializer(), toDto(doc)))
        File(tmp, FsUtil.COMPLETE_MARKER).writeBytes(ByteArray(0))
        commit(dir, tmp)
    }

    /**
     * Loads a project directory written by [save] (schema 1 or 2). Recovers from an interrupted save first.
     * Nodes of unknown kind are skipped with a [warn]ing.
     */
    fun load(dir: File): Document {
        recover(dir)
        val m = manifestJson.decodeFromString(ManifestDto.serializer(), File(dir, MANIFEST).readText())
        val type = ProjectType.entries.firstOrNull { it.name.equals(m.type, ignoreCase = true) }
            ?: ProjectType.REGULAR
        val doc = Document(m.width, m.height, id = m.id, name = m.name, type = type, dpi = m.dpi)
        doc.created = m.created
        doc.modified = m.modified
        doc.paper.color = parseHex(m.paper.color) ?: Rgba.WHITE
        doc.paper.visible = m.paper.visible
        doc.paper.textureId = m.paper.textureId
        doc.paper.textureDepth = m.paper.texDepth
        doc.paper.textureOpacity = m.paper.texOpacity
        doc.paper.textureScale = m.paper.texScale
        val layersDir = File(dir, LAYERS)
        val masksDir = File(dir, MASKS)
        for (n in m.nodes) fromDto(n, doc, layersDir, masksDir)?.let { doc.root.add(it) }
        doc.activeId = m.activeId?.takeIf { doc.findNode(it) != null }
        doc.activeTarget =
            if (m.activeTarget == "mask" && doc.activeNode?.mask != null) EditTarget.MASK else EditTarget.LAYER
        m.selection?.let {
            readMaskTiles(doc.selection.mask, File(dir, SELECTION))
            doc.selection.active = it.active
        }
        doc.guides = m.guides
        return doc
    }

    /** Writes [projectDir] as a `.pnt` ZIP: tiles stored uncompressed (already LZ4), the rest deflated. */
    fun exportPnt(projectDir: File, out: OutputStream) {
        recover(projectDir)
        val root = projectDir.absoluteFile
        val zip = ZipOutputStream(out)
        val files = root.walkTopDown()
            .filter { it.isFile && it.name != FsUtil.COMPLETE_MARKER }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        for (f in files) {
            val entry = ZipEntry(f.relativeTo(root).invariantSeparatorsPath)
            entry.time = f.lastModified()
            if (f.name == TILES_BIN) {
                val crc = CRC32()
                f.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        crc.update(buf, 0, r)
                    }
                }
                entry.method = ZipEntry.STORED
                entry.size = f.length()
                entry.compressedSize = f.length()
                entry.crc = crc.value
            } else {
                entry.method = ZipEntry.DEFLATED
            }
            zip.putNextEntry(entry)
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /**
     * Unpacks a `.pnt` archive into [targetDir], replacing it atomically. Rejects entries
     * that would escape the target directory. The stream is not closed.
     */
    fun importPnt(input: InputStream, targetDir: File) {
        recover(targetDir)
        val tmp = FsUtil.tmpOf(targetDir)
        FsUtil.deleteRecursively(tmp)
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
        val base = tmp.canonicalFile
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: break
            val f = File(base, e.name).canonicalFile
            if (!f.toPath().startsWith(base.toPath()) || (f == base && !e.isDirectory)) {
                throw IOException("illegal zip entry: ${e.name}")
            }
            if (f.name == FsUtil.COMPLETE_MARKER) continue
            if (e.isDirectory) {
                f.mkdirs()
            } else {
                f.parentFile.mkdirs()
                FileOutputStream(f).use { zip.copyTo(it) }
            }
            zip.closeEntry()
        }
        if (!File(tmp, MANIFEST).isFile) {
            FsUtil.deleteRecursively(tmp)
            throw IOException("archive has no $MANIFEST")
        }
        File(tmp, FsUtil.COMPLETE_MARKER).writeBytes(ByteArray(0))
        commit(targetDir, tmp, carryOver = false)
    }

    /**
     * Brings [dir] to a consistent state after a crash during [save]: promotes a complete
     * `<dir>.tmp`, or restores `<dir>.old`, and removes leftovers.
     */
    internal fun recover(dir: File) {
        val tmp = FsUtil.tmpOf(dir)
        val old = FsUtil.oldOf(dir)
        if (!dir.exists()) {
            if (File(tmp, FsUtil.COMPLETE_MARKER).isFile) {
                FsUtil.move(tmp, dir)
                finishCommit(dir, old, carryOver = true)
            } else if (old.exists()) {
                FsUtil.move(old, dir)
                File(dir, FsUtil.COMPLETE_MARKER).delete()
            }
        } else if (old.exists()) {
            finishCommit(dir, old, carryOver = true)
        }
        FsUtil.deleteRecursively(tmp)
    }

    private fun commit(dir: File, tmp: File, carryOver: Boolean = true) {
        val old = FsUtil.oldOf(dir)
        FsUtil.deleteRecursively(old)
        if (dir.exists()) FsUtil.move(dir, old)
        try {
            FsUtil.move(tmp, dir)
        } catch (e: IOException) {
            if (!dir.exists() && old.exists()) FsUtil.move(old, dir)
            throw e
        }
        finishCommit(dir, old, carryOver)
    }

    private fun finishCommit(dir: File, old: File, carryOver: Boolean) {
        if (old.exists() && carryOver) {
            old.listFiles()?.forEach { f ->
                val target = File(dir, f.name)
                if (f.name !in OWNED && !target.exists()) FsUtil.move(f, target)
            }
        }
        FsUtil.deleteRecursively(old)
        File(dir, FsUtil.COMPLETE_MARKER).delete()
    }

    private fun writeTiles(grid: TileGrid, layerDir: File) {
        layerDir.mkdirs()
        val entries = grid.entries()
            .filter { !it.value.isEmpty() }
            .sortedWith(compareBy({ it.key.ty }, { it.key.tx }))
        val index = ByteBuffer.allocate(entries.size * INDEX_ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val raw = ByteBuffer.allocate(TILE_RAW_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val halves = ShortArray(Tile.CHANNELS)
        var offset = 0L
        BufferedOutputStream(FileOutputStream(File(layerDir, TILES_BIN)), 256 * 1024).use { bin ->
            for ((key, tile) in entries) {
                require(key.tx in 0..0xFFFF && key.ty in 0..0xFFFF) { "tile out of range: $key" }
                raw.clear()
                tile.copyHalvesTo(halves)
                raw.asShortBuffer().put(halves)
                val compressed = ByteArrayOutputStream(TILE_RAW_BYTES / 4)
                LZ4FrameOutputStream(compressed).use { it.write(raw.array(), 0, TILE_RAW_BYTES) }
                val bytes = compressed.toByteArray()
                bin.write(bytes)
                index.putShort(key.tx.toShort())
                index.putShort(key.ty.toShort())
                index.putInt(offset.toInt())
                index.putInt(bytes.size)
                offset += bytes.size
                require(offset <= 0xFFFFFFFFL) { "tiles.bin exceeds 4 GiB" }
            }
        }
        File(layerDir, TILES_IDX).writeBytes(index.array())
    }

    private fun readTiles(grid: TileGrid, layerDir: File) {
        val idxFile = File(layerDir, TILES_IDX)
        val binFile = File(layerDir, TILES_BIN)
        if (!idxFile.isFile || !binFile.isFile) return
        val index = ByteBuffer.wrap(idxFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val raw = ByteArray(TILE_RAW_BYTES)
        RandomAccessFile(binFile, "r").use { bin ->
            while (index.remaining() >= INDEX_ENTRY_BYTES) {
                val tx = index.short.toInt() and 0xFFFF
                val ty = index.short.toInt() and 0xFFFF
                val offset = index.int.toLong() and 0xFFFFFFFFL
                val length = index.int.toLong() and 0xFFFFFFFFL
                val compressed = ByteArray(length.toInt())
                bin.seek(offset)
                bin.readFully(compressed)
                DataInputStream(LZ4FrameInputStream(ByteArrayInputStream(compressed))).use { it.readFully(raw) }
                val halves = ShortArray(Tile.CHANNELS)
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(halves)
                grid.put(TileKey(tx, ty), Tile.ofHalves(halves))
            }
        }
    }

    private fun writeMaskTiles(grid: MaskGrid, dir: File) {
        val entries = grid.entries()
            .filter { !it.value.isUniform(grid.defaultValue) }
            .sortedWith(compareBy({ it.key.ty }, { it.key.tx }))
        if (entries.isEmpty()) return
        dir.mkdirs()
        val index = ByteBuffer.allocate(entries.size * INDEX_ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0L
        BufferedOutputStream(FileOutputStream(File(dir, TILES_BIN)), 64 * 1024).use { bin ->
            for ((key, tile) in entries) {
                require(key.tx in 0..0xFFFF && key.ty in 0..0xFFFF) { "tile out of range: " + key }
                val compressed = ByteArrayOutputStream(MASK_RAW_BYTES / 8)
                LZ4FrameOutputStream(compressed).use { it.write(tile.data) }
                val bytes = compressed.toByteArray()
                bin.write(bytes)
                index.putShort(key.tx.toShort())
                index.putShort(key.ty.toShort())
                index.putInt(offset.toInt())
                index.putInt(bytes.size)
                offset += bytes.size
                require(offset <= 0xFFFFFFFFL) { "tiles.bin exceeds 4 GiB" }
            }
        }
        File(dir, TILES_IDX).writeBytes(index.array())
    }

    private fun readMaskTiles(grid: MaskGrid, dir: File) {
        val idxFile = File(dir, TILES_IDX)
        val binFile = File(dir, TILES_BIN)
        if (!idxFile.isFile || !binFile.isFile) return
        val index = ByteBuffer.wrap(idxFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        RandomAccessFile(binFile, "r").use { bin ->
            while (index.remaining() >= INDEX_ENTRY_BYTES) {
                val tx = index.short.toInt() and 0xFFFF
                val ty = index.short.toInt() and 0xFFFF
                val offset = index.int.toLong() and 0xFFFFFFFFL
                val length = index.int.toLong() and 0xFFFFFFFFL
                val compressed = ByteArray(length.toInt())
                bin.seek(offset)
                bin.readFully(compressed)
                val raw = ByteArray(MASK_RAW_BYTES)
                DataInputStream(LZ4FrameInputStream(ByteArrayInputStream(compressed))).use { it.readFully(raw) }
                grid.put(TileKey(tx, ty), MaskTile(raw))
            }
        }
    }

    private fun toDto(doc: Document) = ManifestDto(
        id = doc.id,
        name = doc.name,
        type = doc.type.name.lowercase(),
        width = doc.width,
        height = doc.height,
        dpi = doc.dpi,
        created = doc.created,
        modified = doc.modified,
        paper = PaperDto(
            color = toHex(doc.paper.color),
            visible = doc.paper.visible,
            textureId = doc.paper.textureId,
            texDepth = doc.paper.textureDepth,
            texOpacity = doc.paper.textureOpacity,
            texScale = doc.paper.textureScale,
        ),
        activeId = doc.activeId,
        activeTarget = if (doc.activeTarget == EditTarget.MASK) "mask" else "layer",
        nodes = doc.root.children.map(::toDto),
        selection = if (doc.selection.isEmpty) null else SelectionDto(doc.selection.active),
        guides = doc.guides,
    )

    private fun toDto(n: Node): NodeDto = NodeDto(
        id = n.id,
        name = n.name,
        kind = when (n) {
            is GroupNode -> NodeKind.GROUP
            is RasterLayer -> NodeKind.RASTER
            is AdjustmentLayer -> NodeKind.ADJUSTMENT
        },
        visible = n.visible,
        opacity = n.opacity,
        blendMode = n.blendMode.id,
        clipped = n.clipped,
        hideInRecording = n.hideInRecording,
        locked = n.locked,
        alphaLock = (n as? RasterLayer)?.alphaLock ?: false,
        expanded = (n as? GroupNode)?.expanded ?: true,
        colorTag = n.colorTag.name.lowercase(),
        reference = n.reference,
        mask = n.mask?.let { MaskDto(it.enabled, it.linked, it.density, it.tiles.defaultValue.toInt() and 0xFF) },
        adjust = (n as? AdjustmentLayer)?.let { AdjustCodec.toJson(it.params) },
        children = (n as? GroupNode)?.children?.map(::toDto) ?: emptyList(),
    )

    private fun fromDto(d: NodeDto, doc: Document, layersDir: File, masksDir: File): Node? {
        val node: Node = when (d.kind) {
            NodeKind.GROUP -> GroupNode(d.id, d.name).also { g ->
                g.expanded = d.expanded
                for (c in d.children) fromDto(c, doc, layersDir, masksDir)?.let { g.add(it) }
            }
            NodeKind.RASTER -> {
                val grid = TileGrid(doc.width, doc.height)
                readTiles(grid, File(layersDir, safeName(d.id)))
                RasterLayer(d.id, d.name, grid).also { it.alphaLock = d.alphaLock }
            }
            NodeKind.ADJUSTMENT -> AdjustmentLayer(
                d.id, d.name, d.adjust?.let(AdjustCodec::fromJson) ?: UnknownAdjust(JsonObject(emptyMap())),
            )
            else -> {
                warn("skipping node " + d.id + " of unknown kind '" + d.kind + "'")
                return null
            }
        }
        d.mask?.let { md ->
            val grid = MaskGrid(doc.width, doc.height, md.default.coerceIn(0, 255).toByte())
            readMaskTiles(grid, File(masksDir, safeName(d.id)))
            node.mask = LayerMask(grid).also { it.enabled = md.enabled; it.linked = md.linked; it.density = md.density }
        }
        node.colorTag = ColorTag.entries.firstOrNull { it.name.equals(d.colorTag, ignoreCase = true) } ?: ColorTag.NONE
        node.reference = d.reference
        node.visible = d.visible
        node.opacity = d.opacity
        node.blendMode = BlendMode.fromId(d.blendMode)
        node.clipped = d.clipped
        node.hideInRecording = d.hideInRecording
        node.locked = d.locked
        return node
    }

    private fun safeName(id: String): String {
        require(id.isNotEmpty() && id.none { it == '/' || it == '\\' || it == ':' } && id != "." && id != "..") {
            "invalid node id: $id"
        }
        return id
    }

    private fun toHex(c: Rgba): String {
        fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        return "#%02X%02X%02X%02X".format(b(c.r), b(c.g), b(c.b), b(c.a))
    }

    private fun parseHex(s: String): Rgba? {
        val h = s.removePrefix("#")
        if (h.length != 6 && h.length != 8) return null
        val v = h.toLongOrNull(16) ?: return null
        val full = if (h.length == 6) (v shl 8) or 0xFF else v
        fun c(shift: Int) = ((full shr shift) and 0xFF) / 255f
        return Rgba(c(24), c(16), c(8), c(0))
    }
}
