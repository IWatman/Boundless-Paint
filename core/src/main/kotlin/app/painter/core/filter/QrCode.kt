package app.painter.core.filter

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A small QR Code Model 2 encoder for the QR generator filter (byte mode, error correction level L,
 * versions 1..40). It is self-contained so the filter has no third-party dependency, and it only produces the
 * module matrix; the filter sizes and positions it on the canvas.
 *
 * The text is encoded as UTF-8. Text longer than the largest symbol is truncated at a UTF-8 character
 * boundary, which is the only lossy case; the filter dialog limits the input so it does not happen there.
 */
internal class QrCode private constructor(val size: Int, private val modules: BooleanArray) {

    /** True when the module at [x],[y] is dark; coordinates are inside the symbol without a quiet zone. */
    fun dark(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        private const val ECC_FORMAT_BITS = 1 // Error correction level L.

        // Error correction codewords per block and the number of blocks, indexed by version, for level L.
        private val ECC_PER_BLOCK = intArrayOf(
            -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28,
            28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        )
        private val BLOCKS = intArrayOf(
            -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7,
            8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
        )

        /** Encodes [text] into a module matrix, picking the smallest version that fits. */
        fun encode(text: String): QrCode {
            val data = fitText(text)
            val version = chooseVersion(data.size)
            val codewords = addEccAndInterleave(buildData(data, version), version)
            return draw(version, codewords)
        }

        /** UTF-8 bytes, trimmed to the largest symbol at a character boundary when necessary. */
        private fun fitText(text: String): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (fitsVersion(bytes.size, 40)) return bytes
            val maxBytes = (dataCodewords(40) * 8 - 4 - 16) / 8
            var n = min(bytes.size, maxBytes)
            // Never cut a multi-byte UTF-8 sequence in half.
            while (n > 0 && (bytes[n].toInt() and 0xC0) == 0x80) n--
            return bytes.copyOf(n)
        }

        private fun chooseVersion(byteCount: Int): Int {
            for (version in 1..40) if (fitsVersion(byteCount, version)) return version
            return 40
        }

        private fun fitsVersion(byteCount: Int, version: Int): Boolean {
            val lengthBits = if (version <= 9) 8 else 16
            return 4 + lengthBits + byteCount * 8 <= dataCodewords(version) * 8
        }

        /** Number of data modules (without error correction) of a version. */
        private fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int): Int =
            rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

        /** Mode, length, data, terminator and pad bytes as the version's data codewords. */
        private fun buildData(bytes: ByteArray, version: Int): IntArray {
            val data = IntArray(dataCodewords(version))
            var bit = 0
            fun append(value: Int, count: Int) {
                for (i in count - 1 downTo 0) {
                    if ((value ushr i) and 1 != 0) {
                        data[bit ushr 3] = data[bit ushr 3] or (1 shl (7 - (bit and 7)))
                    }
                    bit++
                }
            }
            append(0b0100, 4) // Byte mode.
            append(bytes.size, if (version <= 9) 8 else 16)
            for (b in bytes) append(b.toInt() and 0xFF, 8)
            val capacity = data.size * 8
            append(0, min(4, capacity - bit))
            append(0, (8 - bit % 8) % 8)
            var pad = 0xEC
            while (bit < capacity) {
                append(pad, 8)
                pad = if (pad == 0xEC) 0x11 else 0xEC
            }
            return data
        }

        /** Reed-Solomon blocks of the data, interleaved as the standard requires. */
        private fun addEccAndInterleave(data: IntArray, version: Int): IntArray {
            val blocks = BLOCKS[version]
            val eccLen = ECC_PER_BLOCK[version]
            val rawCodewords = rawDataModules(version) / 8
            val shortBlocks = blocks - rawCodewords % blocks
            val shortBlockLen = rawCodewords / blocks
            val divisor = rsDivisor(eccLen)
            val parts = Array(blocks) { IntArray(shortBlockLen + 1) }
            var offset = 0
            for (i in 0 until blocks) {
                val dataLen = shortBlockLen - eccLen + if (i < shortBlocks) 0 else 1
                val dat = IntArray(dataLen) { data[offset + it] }
                offset += dataLen
                val ecc = rsRemainder(dat, divisor)
                for (j in dat.indices) parts[i][j] = dat[j]
                // Short blocks carry one padding byte just before their ECC, which interleaving skips.
                for (j in 0 until eccLen) parts[i][parts[i].size - eccLen + j] = ecc[j]
            }
            val result = IntArray(rawCodewords)
            var out = 0
            for (i in 0..shortBlockLen) {
                for (j in 0 until blocks) {
                    if (i != shortBlockLen - eccLen || j >= shortBlocks) {
                        result[out++] = parts[j][i]
                    }
                }
            }
            return result
        }

        /** Generator polynomial of the Reed-Solomon code for [degree] ECC codewords. */
        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMultiply(result[j] and 0xFF, root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b and 0xFF) xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) {
                    result[i] = result[i] xor gfMultiply(divisor[i] and 0xFF, factor)
                }
            }
            return result
        }

        /** Multiplication in GF(2^8) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1. */
        private fun gfMultiply(xIn: Int, yIn: Int): Int {
            var z = 0
            val x = xIn and 0xFF
            val y = yIn and 0xFF
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        /** Draws function patterns, the codewords and the best of the eight data masks. */
        private fun draw(version: Int, codewords: IntArray): QrCode {
            val size = version * 4 + 17
            val modules = BooleanArray(size * size)
            val function = BooleanArray(size * size)

            fun set(x: Int, y: Int, dark: Boolean) {
                if (x < 0 || y < 0 || x >= size || y >= size) return
                modules[y * size + x] = dark
                function[y * size + x] = true
            }

            // Timing patterns run through the middle; finders are drawn afterwards and overwrite them.
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            fun finder(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val dist = max(abs(dx), abs(dy))
                    set(cx + dx, cy + dy, dist != 2 && dist != 4)
                }
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)

            val align = alignmentPositions(version, size)
            for (ay in align) for (ax in align) {
                // The three corners already hold finder patterns.
                if ((ax == 6 && ay == 6) || (ax == 6 && ay == size - 7) || (ax == size - 7 && ay == 6)) continue
                for (dy in -2..2) for (dx in -2..2) {
                    val dist = max(abs(dx), abs(dy))
                    set(ax + dx, ay + dy, dist != 1)
                }
            }

            fun format(mask: Int) {
                val bits = formatBits(mask)
                fun bit(i: Int) = (bits ushr i) and 1 != 0
                for (i in 0..5) set(8, i, bit(i))
                set(8, 7, bit(6))
                set(8, 8, bit(7))
                set(7, 8, bit(8))
                for (i in 9 until 15) set(14 - i, 8, bit(i))
                for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
                for (i in 8 until 15) set(8, size - 15 + i, bit(i))
                set(8, size - 8, true) // The always-dark module.
            }
            format(0)

            if (version >= 7) {
                val bits = versionBits(version)
                for (i in 0 until 18) {
                    val dark = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, dark)
                    set(b, a, dark)
                }
            }

            var bit = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    for (j in 0 until 2) {
                        val x = right - j
                        if (!function[y * size + x] && bit < codewords.size * 8) {
                            modules[y * size + x] =
                                ((codewords[bit ushr 3] ushr (7 - (bit and 7))) and 1) != 0
                            bit++
                        }
                    }
                }
                right -= 2
            }

            fun applyMask(mask: Int) {
                for (y in 0 until size) for (x in 0 until size) {
                    if (function[y * size + x]) continue
                    val flip = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (flip) modules[y * size + x] = !modules[y * size + x]
                }
            }

            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask)
                format(mask)
                val penalty = penalty(modules, size)
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    bestMask = mask
                }
                applyMask(mask) // XOR is its own inverse.
            }
            applyMask(bestMask)
            format(bestMask)
            return QrCode(size, modules)
        }

        private fun alignmentPositions(version: Int, size: Int): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = size - 7
            for (i in count - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun formatBits(mask: Int): Int {
            val data = (ECC_FORMAT_BITS shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        private fun versionBits(version: Int): Int {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            return (version shl 12) or rem
        }

        private fun penalty(modules: BooleanArray, size: Int): Int {
            var result = 0
            // N1: runs of five or more same-coloured modules in a row or column.
            for (y in 0 until size) {
                var run = 1
                for (x in 1 until size) {
                    if (modules[y * size + x] == modules[y * size + x - 1]) {
                        run++
                    } else {
                        if (run >= 5) result += 3 + (run - 5)
                        run = 1
                    }
                }
                if (run >= 5) result += 3 + (run - 5)
            }
            for (x in 0 until size) {
                var run = 1
                for (y in 1 until size) {
                    if (modules[y * size + x] == modules[(y - 1) * size + x]) {
                        run++
                    } else {
                        if (run >= 5) result += 3 + (run - 5)
                        run = 1
                    }
                }
                if (run >= 5) result += 3 + (run - 5)
            }
            // N2: 2x2 blocks of one colour.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1] &&
                    c == modules[(y + 1) * size + x] &&
                    c == modules[(y + 1) * size + x + 1]
                ) {
                    result += 3
                }
            }
            // N3: finder-like 1:1:3:1:1 runs with four light modules on either side.
            var row = 0
            var col = 0
            for (y in 0 until size) {
                row = 0
                col = 0
                for (x in 0 until size) {
                    row = ((row shl 1) or (if (modules[y * size + x]) 1 else 0)) and 0x7FF
                    col = ((col shl 1) or (if (modules[x * size + y]) 1 else 0)) and 0x7FF
                    if (x >= 10) {
                        if (row == 0x5D0 || row == 0x05D) result += 40
                        if (col == 0x5D0 || col == 0x05D) result += 40
                    }
                }
            }
            // N4: deviation from an even split of dark and light modules.
            var dark = 0
            for (m in modules) if (m) dark++
            val total = size * size
            val percent = dark * 100.0 / total
            result += floor(abs(percent - 50.0) / 5.0).toInt() * 10
            return result
        }
    }
}
