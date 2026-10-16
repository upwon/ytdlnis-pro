package com.deniscerri.ytdl.dubbing

/**
 * Version 2 of the `Sec-MS-GEC` token the Edge read-aloud service requires (`Sec-MS-GEC-Version: 2-…`).
 *
 * Ported from edge-tts (`src/edge_tts/drm.py`, generate_sec_ms_gec_v2): the input is still the 5-minute-rounded
 * Windows file time followed by the client token, but it is hashed with a SHA-256 variant (pre-mixed midstate,
 * one altered round constant) and prefixed with a 64-bit "CS64" checksum: `<16 hex>-<64 hex>`.
 */
internal object EdgeGec {
    private const val M = 0xFFFFFFFFL

    private val INITIAL = longArrayOf(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val STATE = longArrayOf(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    )
    private val SCHEDULE = longArrayOf(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    )
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    )
    private const val ADJUSTMENT = 0xC9DC06A7
    private val BLOCK_K = K.copyOf().also { it[14] = ADJUSTMENT }
    private const val SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0L

    /** (coefficient, offset) pairs. */
    private val CS64 = arrayOf(
        0xE82E91B1L to 0xB32700D0L, 0x44DE24C6L to 0x88DFF51DL, 0x3BEB65BCL to 0x29514038L,
        0xC89C5933L to 0xB328CBDFL, 0x8D1EC211L to 0xD405B4D5L, 0x314EF5D5L to 0x26E55F3CL,
        0xE92491B1L to 0x607801C6L, 0xF0A5B9D5L to 0x82B2C1FBL, 0x06064CF3L to 0xFA51A3DAL,
        0xDA79FA60L to 0x207CC464L, 0xBED20560L to 0x3C3C74F3L, 0x89F9C9F8L to 0xD2D14B61L,
        0x35FA38C6L to 0xBB04A2C1L, 0xCB49E3C6L to 0xC5626167L,
    )
    private const val CS64_FACTOR = 0x82893933
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1A
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    private fun rotr(x: Long, n: Int): Long = ((x ushr n) or (x shl (32 - n))) and M
    private fun rol16(x: Long): Long { val v = x and M; return ((v shl 16) or (v ushr 16)) and M }
    private fun sigma0(v: Long) = (rotr(v, 7) xor rotr(v, 18) xor (v ushr 3)) and M
    private fun sigma1(v: Long) = (rotr(v, 17) xor rotr(v, 19) xor (v ushr 10)) and M

    private fun rounds(state: LongArray, w: List<Long>, firstRound: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (firstRound == 14) BLOCK_K else K
        for (i in firstRound until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = g xor (e and (f xor g))
            val t1 = (h + s1 + ch + k[i] + w[i]) and M
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) or (c and (a or b))
            val t2 = (s0 + maj) and M
            h = g; g = f; f = e; e = (d + t1) and M
            d = c; c = b; b = a; a = (t1 + t2) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: MutableList<Long>, firstWord: Int) {
        for (i in firstWord until 64) {
            w.add((sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]) and M)
        }
    }

    private fun be(bytes: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until to) v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        return v
    }

    private fun init(data7: ByteArray): LongArray {
        val w = MutableList(14) { 0L }
        w.add(be(data7, 0, 3))
        w.add(be(data7, 3, 7))
        for (i in 16 until 30) {
            var v = SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) sigma1(w[i - 2]) xor SIGMA1_ADJUSTMENT else sigma1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) sigma0(w[i - 15]) xor SIGMA0_ADJUSTMENT else sigma0(w[i - 15])
            w.add(v and M)
        }
        w.add((sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + ADJUSTMENT - K[14]) and M)
        expand(w, 31)
        val working = rounds(STATE, w, 14)
        return LongArray(8) { (INITIAL[it] + working[it]) and M }
    }

    internal fun sha256(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val padLen = Math.floorMod(55 - length, 64)
        val padded = ByteArray(data.size - 7 + 1 + padLen + 8)
        System.arraycopy(data, 7, padded, 0, data.size - 7)
        padded[data.size - 7] = 0x80.toByte()
        val bits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        var state = init(data.copyOfRange(0, 7))
        for (off in padded.indices step 64) {
            val w = MutableList(16) { be(padded, off + it * 4, off + it * 4 + 4) }
            expand(w, 16)
            val working = rounds(state, w, 0)
            state = LongArray(8) { (state[it] + working[it]) and M }
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (state[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    private fun le32(bytes: ByteArray, off: Int): Long {
        var v = 0L
        for (j in 3 downTo 0) v = (v shl 8) or (bytes[off + j].toLong() and 0xFF)
        return v
    }

    private fun wordsLe4Even(data: ByteArray): List<Long> {
        val padded = data.copyOf(data.size + Math.floorMod(-data.size, 4))
        val words = (padded.indices step 4).map { le32(padded, it) }.toMutableList()
        if (words.size % 2 == 1) words.add(0L)
        return words
    }

    internal fun cs64(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val keyA = le32(digest, 0) or 1L
        val keyB = le32(digest, 4) or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and M, (keyB * CS64_FACTOR) and M)
        val terms = CS64.mapIndexed { i, (coef, _) -> (coef * factors[i % 2]) and M }.toMutableList()
        val offsets = CS64.map { it.second }.toMutableList()
        wordsLe4Even(byteArrayOf(0) + data).forEachIndexed { i, word ->
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FB else 0xC31713DB)
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and M)
            offsets.add(offset and M)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        for (i in terms.indices step 2) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M)
            var q = (t * 0x76F50F34) and M
            swapAcc += (rol16(q) * 0xC3EFEA97 + q) and M
            t = rol16((offsets[i + 1] + y) and M)
            q = (t * 0x1881CE3C) and M
            swapLast = (rol16(q) * 0x35BD1EC9 + q) and M
            swapAcc = (swapAcc + swapLast) and M

            state = (state * keyA + x) and M
            state = (rol16(state) * 0xCF98B111) and M
            state = (rol16(state) * 0x87085B9F) and M
            state = (rol16(state) * 0x12CEB96D) and M
            state = (rol16(state) * 0x257E1D83) and M
            acc += state
            t = (state * keyB + y) and M
            t = (rol16(t) * 0xA27416F5) and M
            t = (rol16(t) * 0xD38396FF) and M
            state = (rol16((rol16(t) * 0x7C932B89) and M) * 0xBFA49F69) and M
            acc = (acc + state) and M
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    /** Token for the given Windows-file-time input string (ticks + client token). */
    fun token(input: String): String {
        val data = input.toByteArray(Charsets.US_ASCII)
        val digest = sha256(data)
        val (lo, hi) = cs64(data, digest)
        val cs = ByteArray(8)
        for (j in 0 until 4) {
            cs[j] = (lo ushr (8 * j)).toByte()
            cs[4 + j] = (hi ushr (8 * j)).toByte()
        }
        return hex(cs) + "-" + hex(digest)
    }
}
