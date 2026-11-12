package org.openwild.app.core

/**
 * Bech32 (BIP-173), used to turn a Lightning Address into an LNURL the wallet can pay.
 * The app never contacts the address host; the wallet does, after the user opens it.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, pad = true)
        val checksum = createChecksum(hrp, values)
        return hrp + "1" + (values + checksum).joinToString("") { CHARSET[it].toString() }
    }

    fun decode(bech: String): Pair<String, ByteArray> {
        val lower = bech.lowercase()
        val sep = lower.lastIndexOf('1')
        require(sep > 0) { "missing separator" }
        val hrp = lower.substring(0, sep)
        val data = IntArray(lower.length - sep - 1) { i ->
            val c = CHARSET.indexOf(lower[sep + 1 + i])
            require(c >= 0) { "bad character" }
            c
        }
        require(data.size >= 6) { "short" }
        require(polymod(expandHrp(hrp) + data.toList()) == 1) { "bad checksum" }
        val payload = data.copyOfRange(0, data.size - 6).toList()
        val bytes = convertBits(payload, 5, 8, pad = false)
        return hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if (((top shr i) and 1) != 0) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun expandHrp(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }

    private fun createChecksum(hrp: String, data: List<Int>): List<Int> {
        val polymod = polymod(expandHrp(hrp) + data + List(6) { 0 }) xor 1
        return List(6) { i -> (polymod shr (5 * (5 - i))) and 31 }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): List<Int> =
        convertBits(data.map { it.toInt() and 0xFF }, from, to, pad)

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int> {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        val maxAcc = (1 shl (from + to - 1)) - 1
        for (value in data) {
            require(value in 0 until (1 shl from))
            acc = ((acc shl from) or value) and maxAcc
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc shr bits) and maxv
            }
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and maxv
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            throw IllegalArgumentException("padding")
        }
        return out
    }
}

object LightningAddress {
    /** `lightning:` URI whose payload is the LNURL-pay endpoint for [address] (`user@host`). */
    fun payUri(address: String): String {
        val at = address.indexOf('@')
        require(at > 0 && at < address.lastIndex) { "not a lightning address" }
        val user = address.substring(0, at)
        val host = address.substring(at + 1)
        val url = "https://$host/.well-known/lnurlp/$user"
        return "lightning:" + Bech32.encode("lnurl", url.encodeToByteArray())
    }
}
