package org.openwild.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun packsAndBrainSitAtTheBottomUnderSurvival() {
        assertTrue(HomeLayout.packsAfterSurvival())
        assertTrue(HomeLayout.packsBeforeCredits())
        assertFalse(HomeLayout.sections.indexOf(HomeLayout.BRAIN_AND_PACKS) < HomeLayout.sections.indexOf(HomeLayout.IDENTIFY))
        assertTrue(HomeLayout.sections.indexOf(HomeLayout.BRAIN_AND_PACKS) > HomeLayout.sections.indexOf(HomeLayout.EXPLORE))
    }
}
