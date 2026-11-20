package org.openwild.app.ui

/**
 * Bitcoin donation details shown on the About screen and in the README.
 * The address is a constant. Nothing is fetched, and the app never opens a wallet.
 */
object BitcoinDonation {
    const val ADDRESS: String = "bc1qgj3ce8qeq22s37py9s6m2ksjpz9v3zhls5re3a"

    /** Exact characters drawn in the About screen's donation text. */
    fun displayedAddress(): String = ADDRESS

    /** BIP21 payload for the on-device QR. The human-readable text stays [displayedAddress]. */
    fun qrPayload(): String = "bitcoin:$ADDRESS"
}
