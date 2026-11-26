package com.motorota.intercom

/**
 * Compressão de voz IMA-ADPCM (4 bits por amostra, 4x menor que o áudio cru).
 * Cada pacote leva o próprio estado inicial, então perder um pacote não estraga os próximos.
 * Formato: [prev lo][prev hi][index][0] + amostras (2 por byte).
 */
object Adpcm {
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    class State {
        var prev = 0
        var index = 0
    }

    fun encode(pcm: ShortArray, count: Int, st: State): ByteArray {
        val out = ByteArray(4 + (count + 1) / 2)
        out[0] = (st.prev and 0xff).toByte()
        out[1] = ((st.prev shr 8) and 0xff).toByte()
        out[2] = st.index.toByte()
        out[3] = 0
        var prev = st.prev
        var index = st.index
        for (i in 0 until count) {
            var step = STEP[index]
            var diff = pcm[i] - prev
            var code = 0
            if (diff < 0) { code = 8; diff = -diff }
            var delta = step shr 3
            if (diff >= step) { code = code or 4; diff -= step; delta += step }
            step = step shr 1
            if (diff >= step) { code = code or 2; diff -= step; delta += step }
            step = step shr 1
            if (diff >= step) { code = code or 1; delta += step }
            prev = if ((code and 8) != 0) prev - delta else prev + delta
            prev = prev.coerceIn(-32768, 32767)
            index = (index + INDEX[code]).coerceIn(0, 88)
            val pos = 4 + i / 2
            out[pos] = if (i % 2 == 0) (code and 0x0f).toByte() else (out[pos].toInt() or (code shl 4)).toByte()
        }
        st.prev = prev
        st.index = index
        return out
    }

    fun decode(data: ByteArray): ShortArray {
        if (data.size < 4) return ShortArray(0)
        var prev = ((data[0].toInt() and 0xff) or (data[1].toInt() shl 8)).toShort().toInt()
        var index = (data[2].toInt() and 0xff).coerceIn(0, 88)
        val n = (data.size - 4) * 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val b = data[4 + i / 2].toInt() and 0xff
            val code = if (i % 2 == 0) b and 0x0f else b shr 4
            val step = STEP[index]
            var delta = step shr 3
            if ((code and 4) != 0) delta += step
            if ((code and 2) != 0) delta += step shr 1
            if ((code and 1) != 0) delta += step shr 2
            prev = if ((code and 8) != 0) prev - delta else prev + delta
            prev = prev.coerceIn(-32768, 32767)
            index = (index + INDEX[code]).coerceIn(0, 88)
            out[i] = prev.toShort()
        }
        return out
    }
}
