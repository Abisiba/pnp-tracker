package dev.pnptracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppInfoTest {
    @Test
    fun `current application id is usable as an xdg directory name`() {
        assertEquals("pnp-tracker", AppInfo.Current.id)
    }

    @Test
    fun `current version is the build's own and follows major minor patch`() {
        assertEquals(APPLICATION_VERSION, AppInfo.Current.version)
        assertTrue(Regex("\\d+\\.\\d+\\.\\d+").matches(AppInfo.Current.version))
    }

    @Test
    fun `application id with upper case or spaces is rejected`() {
        assertFailsWith<IllegalArgumentException> { AppInfo(id = "PnP Tracker", version = "0.1.0") }
        assertFailsWith<IllegalArgumentException> { AppInfo(id = "", version = "0.1.0") }
    }

    @Test
    fun `version that is not major minor patch is rejected`() {
        assertFailsWith<IllegalArgumentException> { AppInfo(id = "pnp-tracker", version = "0.1") }
        assertFailsWith<IllegalArgumentException> { AppInfo(id = "pnp-tracker", version = "v0.1.0") }
    }
}
