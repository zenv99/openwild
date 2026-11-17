package org.openwild.app.ui

/**
 * Lightning tips: most wallets don't pre-fill a bare Lightning address (user@domain) passed as
 * "lightning:user@domain", but every LNURL wallet understands LNURL-pay. LUD-16 maps the address to
 * https://domain/.well-known/lnurlp/user, and LUD-01 bech32-encodes that URL as "LNURL1...".
 */
object Lightning {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun lnurl(address: String): String? {
        val parts = address.trim().split("@")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return bech32("lnurl", "https://${parts[1]}/.well-known/lnurlp/${parts[0]}".toByteArray()).uppercase()
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor gen[i]
        }
        return chk
    }

    private fun bech32(hrp: String, data: ByteArray): String {
        val five = ArrayList<Int>()
        var acc = 0
        var bits = 0
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) { bits -= 5; five += (acc ushr bits) and 31 }
        }
        if (bits > 0) five += (acc shl (5 - bits)) and 31
        val hrpExp = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
        val pm = polymod((hrpExp + five + List(6) { 0 }).toIntArray()) xor 1
        val checksum = (0 until 6).map { (pm ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (five + checksum).joinToString("") { CHARSET[it].toString() }
    }
}
