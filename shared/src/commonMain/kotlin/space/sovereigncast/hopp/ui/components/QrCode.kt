package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/*
 * QR Code encoding, byte mode only — what Hopp shows: a nostrconnect:// address for a signer
 * on the phone to scan (maintainer 2026-10-28: in the browser there was only the text).
 * Ported from Project Nayuki's QR Code generator library (MIT License,
 * https://www.nayuki.io/page/qr-code-generator-library), cut to what Hopp needs.
 */

/** A QR code: [size]×[size] modules, true = dark. */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    enum class Ecc(val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

    companion object {
        /** [text] as UTF-8, in the smallest version that holds it at [ecc]. */
        fun encode(text: String, ecc: Ecc = Ecc.MEDIUM): QrCode = encode(text.encodeToByteArray(), ecc)

        fun encode(data: ByteArray, ecc: Ecc = Ecc.MEDIUM): QrCode {
            var version = 1
            while (true) {
                val capacityBits = dataCodewords(version, ecc) * 8
                val needed = 4 + (if (version <= 9) 8 else 16) + data.size * 8
                if (needed <= capacityBits) break
                require(version < 40) { "too long for a QR code" }
                version++
            }
            val bits = BitBuffer()
            bits.append(0x4, 4) // byte mode
            bits.append(data.size, if (version <= 9) 8 else 16)
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            val capacityBits = dataCodewords(version, ecc) * 8
            bits.append(0, minOf(4, capacityBits - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) { bits.append(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = ByteArray(bits.size / 8)
            for (i in 0 until bits.size) if (bits[i]) codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            return Builder(version, ecc).build(codewords)
        }

        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )
        private val BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )

        private fun eccRow(ecc: Ecc) = when (ecc) { Ecc.LOW -> 0; Ecc.MEDIUM -> 1; Ecc.QUARTILE -> 2; Ecc.HIGH -> 3 }

        internal fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        internal fun dataCodewords(version: Int, ecc: Ecc): Int =
            rawModules(version) / 8 - ECC_PER_BLOCK[eccRow(ecc)][version] * BLOCKS[eccRow(ecc)][version]
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        operator fun get(i: Int) = bits[i]
        fun append(value: Int, length: Int) { for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
    }

    private class Builder(val version: Int, val ecc: Ecc) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun build(data: ByteArray): QrCode {
            drawFunctionPatterns()
            drawCodewords(addEccAndInterleave(data))
            var best = 0
            var minPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask); drawFormatBits(mask)
                val p = penalty()
                if (p < minPenalty) { best = mask; minPenalty = p }
                applyMask(mask) // undo
            }
            applyMask(best); drawFormatBits(best)
            return QrCode(size, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val align = alignmentPositions()
            val n = align.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                alignment(align[i], align[j])
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, d != 2 && d != 4)
            }
        }

        private fun alignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) set(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val num = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2
            val result = IntArray(num)
            result[0] = 6
            var pos = size - 7
            for (i in num - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun drawFormatBits(mask: Int) {
            val data = ecc.formatBits shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = version shl 12 or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3; val b = i / 3
                set(a, b, bit); set(b, a, bit)
            }
        }

        private fun addEccAndInterleave(data: ByteArray): ByteArray {
            val row = eccRow(ecc)
            val numBlocks = BLOCKS[row][version]
            val eccLen = ECC_PER_BLOCK[row][version]
            val raw = rawModules(version) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortLen - eccLen + (if (i < numShort) 0 else 1)
                val dat = data.copyOfRange(k, k + datLen)
                k += datLen
                val block = ByteArray(shortLen + 1)
                dat.copyInto(block)
                rsRemainder(dat, divisor).copyInto(block, block.size - eccLen)
                blocks.add(block)
            }
            val result = ByteArray(raw)
            var idx = 0
            for (i in 0 until blocks[0].size) for (j in blocks.indices) {
                if (i != shortLen - eccLen || j >= numShort) result[idx++] = blocks[j][i]
            }
            return result
        }

        private fun rsDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                result.copyInto(result, 0, 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        private fun penalty(): Int {
            var result = 0
            // Runs of one colour in rows and columns, and finder-like patterns.
            for (horizontal in listOf(true, false)) for (a in 0 until size) {
                var runColor = false
                var run = 0
                val history = IntArray(7)
                for (b in 0 until size) {
                    val dark = if (horizontal) modules[a][b] else modules[b][a]
                    if (dark == runColor) {
                        run++
                        if (run == 5) result += 3 else if (run > 5) result++
                    } else {
                        addHistory(run, history)
                        if (!runColor) result += finderLike(history) * 40
                        runColor = dark
                        run = 1
                    }
                }
                result += terminate(runColor, run, history) * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }

        private fun addHistory(run: Int, history: IntArray) {
            var r = run
            if (history[0] == 0) r += size // light border before the first module
            history.copyInto(history, 1, 0, history.size - 1)
            history[0] = r
        }

        private fun finderLike(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun terminate(runColor: Boolean, run: Int, history: IntArray): Int {
            var r = run
            if (runColor) { addHistory(r, history); r = 0 }
            r += size
            addHistory(r, history)
            return finderLike(history)
        }
    }
}


/** [text] as a QR code, black on white with its quiet zone — readable in dark mode too. */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { runCatching { QrCode.encode(text) }.getOrNull() } ?: return
    Canvas(modifier.aspectRatio(1f).background(Color.White).semantics { contentDescription = description }) {
        val quiet = 4
        val cell = size.minDimension / (qr.size + quiet * 2)
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) {
            // A hair wider than a cell: no light seams between neighbours at fractional sizes.
            drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}
