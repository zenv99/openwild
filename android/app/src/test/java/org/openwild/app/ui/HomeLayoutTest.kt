package org.openwild.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun brainAndPacksSitUnderIdentifyAndAboveSurvival() {
        assertTrue(HomeLayout.brainFollowsIdentify())
        assertTrue(HomeLayout.brainBeforeSurvival())
        assertTrue(HomeLayout.sections.indexOf(HomeLayout.EXPLORE) > HomeLayout.sections.indexOf(HomeLayout.BRAIN_AND_PACKS))
    }
}
