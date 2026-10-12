package com.swiftsstp.client

import android.content.Context
import com.swiftsstp.client.data.ProfileStore
import com.swiftsstp.client.data.UsageEntry
import com.swiftsstp.client.data.UsageStore
import com.swiftsstp.client.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where the app currently is. Screens are plain state, not a nav library. */
sealed class Screen {
    object Home : Screen()
    object Profiles : Screen()
    object Stats : Screen()
    object Log : Screen()
    data class Editor(val profileId: String?) : Screen()
}

/**
 * Application state shared by the tabs: the profile list, lifetime usage, the
 * selected profile for the Home tab, and navigation.
 * The VPN service writes usage through [UsageStore] and bumps [usage] itself.
 */
object AppModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The Windows client opens on the connection view, so Android opens on Home.
    val screen = MutableStateFlow<Screen>(Screen.Home)
    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val usage = MutableStateFlow<Map<String, UsageEntry>>(emptyMap())
    val selectedId = MutableStateFlow<String?>(null)

    /**
     * One-shot "connect now" requests from outside the UI (the
     * quick-settings tile). Bumped by MainActivity when an intent asks
     * for it; consumed once by the Home screen's connect flow so a
     * configuration change cannot re-fire it.
     */
    val connectTick = MutableStateFlow(0)

    @Volatile
    private var appDir: File? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appDir = app.filesDir
        UsageStore.init(app.filesDir)
        // No update check here, on purpose: it is manual-only (see
        // net/UpdateChecker), so launching the app never touches GitHub.
        refreshProfiles()
        refreshUsage()
        if (selectedId.value == null) {
            selectedId.value = profiles.value.firstOrNull()?.id
        }
    }

    private fun dir(): File = checkNotNull(appDir) { "AppModel.init must run first" }

    fun refreshProfiles() {
        runCatching { profiles.value = ProfileStore.load(dir()) }
    }

    fun refreshUsage() {
        runCatching { usage.value = UsageStore.snapshot() }
    }

    fun profile(id: String?): Profile? = profiles.value.firstOrNull { it.id == id }

    fun save(profile: Profile) {
        val next = if (profiles.value.any { it.id == profile.id }) {
            profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            profiles.value + profile
        }
        profiles.value = next
        if (selectedId.value == null) selectedId.value = profile.id
        scope.launch { ProfileStore.save(dir(), next) }
    }

    fun delete(id: String) {
        val next = profiles.value.filterNot { it.id == id }
        profiles.value = next
        if (selectedId.value == id) selectedId.value = next.firstOrNull()?.id
        scope.launch {
            ProfileStore.save(dir(), next)
            UsageStore.forget(id)
            refreshUsage()
        }
    }

    fun duplicate(id: String): Profile? {
        val src = profile(id) ?: return null
        val copy = src.copy(
            id = Profile.newId(),
            name = if (src.name.isBlank()) "Copy" else src.name + " copy",
        )
        save(copy)
        return copy
    }

    /** Called by the service after every usage mutation so the UI follows. */
    fun onUsageChanged() {
        refreshUsage()
    }
}
