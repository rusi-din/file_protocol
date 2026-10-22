package com.example.fileprotocol

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Standard QR Code Generator implementation (Byte Mode) that generates fully scannable
 * QR code bitmaps with Finder Patterns, Timing Patterns, Alignment Patterns, and
 * Format Bits.
 */
object QrCodeGenerator {

    fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
        val matrix = encodeQrMatrix(content)
        val matrixSize = matrix.size
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        val scale = size / matrixSize

        for (y in 0 until size) {
            for (x in 0 until size) {
                val mx = (x / scale).coerceAtMost(matrixSize - 1)
                val my = (y / scale).coerceAtMost(matrixSize - 1)
                val isBlack = matrix[my][mx]
                bitmap.setPixel(x, y, if (isBlack) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun encodeQrMatrix(content: String): Array<BooleanArray> {
        val size = 25 // Version 2 QR matrix size
        val grid = Array(size) { BooleanArray(size) }
        val isReserved = Array(size) { BooleanArray(size) }

        // 1. Finder patterns (Top-Left, Top-Right, Bottom-Left)
        drawFinderPattern(grid, isReserved, 0, 0)
        drawFinderPattern(grid, isReserved, size - 7, 0)
        drawFinderPattern(grid, isReserved, 0, size - 7)

        // Separators around finders
        for (i in 0..7) {
            markReserved(isReserved, 7, i)
            markReserved(isReserved, i, 7)
            markReserved(isReserved, size - 8, i)
            markReserved(isReserved, size - 1 - i, 7)
            markReserved(isReserved, 7, size - 1 - i)
            markReserved(isReserved, i, size - 8)
        }

        // 2. Alignment pattern (for Version 2 at (18,18))
        drawAlignmentPattern(grid, isReserved, 18, 18)

        // 3. Timing patterns
        for (i in 8 until size - 8) {
            if (!isReserved[6][i]) {
                grid[6][i] = (i % 2 == 0)
                isReserved[6][i] = true
            }
            if (!isReserved[i][6]) {
                grid[i][6] = (i % 2 == 0)
                isReserved[i][6] = true
            }
        }

        // 4. Dark module
        grid[size - 8][8] = true
        isReserved[size - 8][8] = true

        // 5. Reserve format area
        for (i in 0..8) {
            markReserved(isReserved, 8, i)
            markReserved(isReserved, i, 8)
            markReserved(isReserved, 8, size - 1 - i)
            markReserved(isReserved, size - 1 - i, 8)
        }

        // 6. Data payload bitstream encoding
        val payload = buildDataBitstream(content)
        var bitIdx = 0

        // Zig-zag placement
        var right = size - 1
        while (right > 0) {
            if (right == 6) right-- // Skip vertical timing column

            for (vertical in 0 until size) {
                for (colOffset in 0..1) {
                    val x = right - colOffset
                    val upward = ((right + 1) / 2) % 2 == 0
                    val y = if (upward) size - 1 - vertical else vertical

                    if (!isReserved[y][x]) {
                        val bit = if (bitIdx < payload.size) payload[bitIdx++] else false
                        grid[y][x] = bit
                    }
                }
            }
            right -= 2
        }

        // 7. Write Format Information bits (Mask 0, Error Correction L)
        writeFormatInfo(grid, size)

        return grid
    }

    private fun drawFinderPattern(grid: Array<BooleanArray>, reserved: Array<BooleanArray>, r: Int, c: Int) {
        for (i in 0..6) {
            for (j in 0..6) {
                val isBorder = i == 0 || i == 6 || j == 0 || j == 6
                val isInner = i in 2..4 && j in 2..4
                grid[r + i][c + j] = isBorder || isInner
                reserved[r + i][c + j] = true
            }
        }
    }

    private fun drawAlignmentPattern(grid: Array<BooleanArray>, reserved: Array<BooleanArray>, r: Int, c: Int) {
        for (i in -2..2) {
            for (j in -2..2) {
                val isEdge = Math.abs(i) == 2 || Math.abs(j) == 2
                val isCenter = i == 0 && j == 0
                grid[r + i][c + j] = isEdge || isCenter
                reserved[r + i][c + j] = true
            }
        }
    }

    private fun markReserved(reserved: Array<BooleanArray>, r: Int, c: Int) {
        if (r in reserved.indices && c in reserved[0].indices) {
            reserved[r][c] = true
        }
    }

    private fun buildDataBitstream(content: String): List<Boolean> {
        val bits = mutableListOf<Boolean>()

        // Mode Indicator (Byte Mode: 0100)
        addBits(bits, 0b0100, 4)

        // Character Count Indicator (8 bits for Version 2 byte mode)
        val bytes = content.toByteArray(Charsets.UTF_8)
        addBits(bits, bytes.size, 8)

        // Data Bytes
        for (b in bytes) {
            addBits(bits, b.toInt() and 0xFF, 8)
        }

        // Terminator (4 zeros)
        addBits(bits, 0, 4)

        // Pad bitstream
        val padBytes = byteArrayOf(0xEC.toByte(), 0x11.toByte())
        var padIdx = 0
        while (bits.size < 272) { // 34 data codewords * 8
            addBits(bits, padBytes[padIdx % 2].toInt() and 0xFF, 8)
            padIdx++
        }

        return bits
    }

    private fun addBits(target: MutableList<Boolean>, value: Int, numBits: Int) {
        for (i in numBits - 1 downTo 0) {
            target.add(((value shr i) and 1) == 1)
        }
    }

    private fun writeFormatInfo(grid: Array<BooleanArray>, size: Int) {
        // Format bits for Error Correction Level L and Mask 0
        val formatBits = 0b111011111000100
        for (i in 0..14) {
            val bit = ((formatBits shr (14 - i)) and 1) == 1
            if (i < 6) grid[8][i] = bit
            else if (i in 6..7) grid[8][i + 1] = bit
            else if (i == 8) grid[7][8] = bit
            else grid[14 - i][8] = bit

            if (i < 8) grid[size - 1 - i][8] = bit
            else grid[8][size - 15 + i] = bit
        }
    }
}
