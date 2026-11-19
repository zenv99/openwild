package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlantNetRequestTest {
    @Test
    fun typedKeyWinsOverABuildSecret() {
        assertEquals("typed-key", PlantNetRequest.resolveKey(" typed-key ", "build-secret"))
        assertEquals("build-secret", PlantNetRequest.resolveKey("  ", "build-secret"))
        assertEquals("", PlantNetRequest.resolveKey("", ""))
    }

    @Test
    fun organsMatchThePhotoThePersonTagged() {
        assertEquals("leaf", PlantNetRequest.organ("LEAF"))
        assertEquals("flower", PlantNetRequest.organ("FLOWER_FRUIT"))
        assertEquals("bark", PlantNetRequest.organ("BARK"))
        assertEquals("auto", PlantNetRequest.organ("WHOLE"))
        assertEquals("auto", PlantNetRequest.organ("CAP"))
        assertEquals("auto", PlantNetRequest.organ("UNDERSIDE"))
    }
}
