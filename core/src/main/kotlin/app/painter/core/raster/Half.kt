package app.painter.core.raster

/** IEEE 754 binary16 <-> binary32 conversion used for tile storage and GPU upload. */
object Half {
    fun fromFloat(value: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        var exp = ((bits ushr 23) and 0xFF) - 127 + 15
        var mant = bits and 0x7FFFFF
        if (((bits ushr 23) and 0xFF) == 0xFF) {
            // Inf / NaN
            return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()
        }
        if (exp <= 0) {
            if (exp < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - exp
            var half = mant ushr shift
            // round to nearest even
            val rem = mant and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }
        if (exp >= 31) return (sign or 0x7C00).toShort()
        var half = (exp shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++
        return (sign or half).toShort()
    }

    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = (bits and 0x8000) shl 16
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        val out = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> {
                var m = mant
                var e = -14
                while ((m and 0x400) == 0) { m = m shl 1; e-- }
                m = m and 0x3FF
                sign or ((e + 127) shl 23) or (m shl 13)
            }
            exp == 31 -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(out)
    }
}
