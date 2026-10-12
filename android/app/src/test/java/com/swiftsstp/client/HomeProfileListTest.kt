package com.swiftsstp.client

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.swiftsstp.client.model.Profile
import com.swiftsstp.client.ui.HomeScreen
import com.swiftsstp.client.ui.SwiftSSTPTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Geometry of the Home profile list, asserted from measured bounds.
 *
 * The list used to be a popup anchored to the badge, so it opened on top
 * of the connect toggle — the one control in the app that must never be
 * covered. It is laid out in flow now: it opens below the badge and
 * borrows its height from the Home column's weighted spacers, so it can
 * push the rest of the column down but never off the screen. Both halves
 * of that are measured here instead of eyeballed, at a normal phone size
 * and at the smallest one the app still claims to support.
 *
 * Everything is measured AFTER the list opens: opening it shifts the
 * whole column (the spacers give up their height), so bounds captured
 * while it was closed describe a layout that no longer exists. The clock
 * runs manually for the same reason: Home keeps a 60s refresh loop in a
 * LaunchedEffect, and with auto-advance the test clock would chase that
 * loop forever instead of going idle.
 */
abstract class HomeProfileListGeometryTest {

    @get:Rule
    val rule = createComposeRule()

    private fun profiles() = listOf(
        Profile(
            id = "a", name = "Office VPN", host = "18.130.233.223",
            port = 443, username = "mohsen", password = "secret",
        ),
        Profile(
            id = "b", name = "Home server", host = "vpn.example.com",
            port = 443, username = "user", password = "secret",
        ),
        Profile(
            id = "c", name = "A very long profile name that must ellipsize",
            host = "long.example-name.com", port = 8443,
            username = "user", password = "secret",
        ),
    )

    private fun setUpHome() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SwiftSSTPTheme {
                HomeScreen(
                    profiles = profiles(),
                    selectedId = "a",
                    vpn = VpnController.Snapshot(),
                    onSelect = {},
                    onConnect = {},
                    onDisconnect = {},
                    onNewProfile = {},
                    onTab = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(100)
    }

    /** Opens the list and waits for the recomposition it triggers. */
    private fun openList() {
        rule.onNodeWithTag("profile-badge-pill").performClick()
        rule.mainClock.advanceTimeBy(100)
        // "Home server" exists only inside the list, so this proves the
        // click opened it rather than merely toggling some state.
        rule.onNodeWithText("Home server").assertExists()
    }

    @Test
    fun listOpensBelowTheBadgeAndNeverCoversTheConnectToggle() {
        setUpHome()
        openList()

        val pill = rule.onNodeWithTag("profile-badge-pill").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag("profile-list").getUnclippedBoundsInRoot()
        val toggle = rule.onNodeWithContentDescription("Connect").getUnclippedBoundsInRoot()

        assertTrue(
            "list must open below the badge (list top ${list.top}, pill bottom ${pill.bottom})",
            list.top >= pill.bottom,
        )
        assertTrue(
            "list must not cover the connect toggle (list bottom ${list.bottom}, toggle top ${toggle.top})",
            list.bottom <= toggle.top,
        )
        assertTrue(
            "connect toggle must stay below the badge (toggle top ${toggle.top}, pill bottom ${pill.bottom})",
            toggle.top >= pill.bottom,
        )
    }

    @Test
    fun listIsFlushWithTheBadgeAndStaysOnScreen() {
        setUpHome()
        openList()

        val pill = rule.onNodeWithTag("profile-badge-pill").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag("profile-list").getUnclippedBoundsInRoot()
        val screen = rule.onRoot().getUnclippedBoundsInRoot()

        assertTrue(
            "list must start at the badge's left edge (list ${list.left}, pill ${pill.left})",
            abs(list.left.value - pill.left.value) <= 1f,
        )
        assertTrue(
            "list must end at the badge's right edge (list ${list.right}, pill ${pill.right})",
            abs(list.right.value - pill.right.value) <= 1f,
        )
        assertTrue(
            "list must stay on screen (bottom ${list.bottom}, screen ${screen.bottom})",
            list.bottom <= screen.bottom,
        )
        // Everything the list pushed down is still readable: the toggle and
        // the IP block are the first two casualties of a list that took
        // more height than the column had to give.
        rule.onNodeWithContentDescription("Connect").assertExists()
        rule.onNodeWithText("YOUR IP ADDRESS").assertExists()
        rule.onNodeWithText("Disconnected").assertExists()
        // A third row may sit below the fold of a short budget, but the
        // second one must be visible without scrolling on both sizes.
        rule.onNodeWithText("Home server").assertExists()
    }
}

/** The size the app is designed around. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeProfileListPhoneTest : HomeProfileListGeometryTest()

/** The smallest screen the app still claims to support (360x640). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class HomeProfileListSmallPhoneTest : HomeProfileListGeometryTest()
