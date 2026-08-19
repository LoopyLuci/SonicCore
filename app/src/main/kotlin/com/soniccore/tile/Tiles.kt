package com.soniccore.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.soniccore.core.audio.routing.AudioRouter
import com.soniccore.core.data.engine.ProfileEngine
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.model.eq.EqSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.soniccore.core.ui.R

/** Toggles the equalizer on the active profile from Quick Settings. */
@AndroidEntryPoint
class EqualizerTileService : TileService() {

    @Inject lateinit var profileEngine: ProfileEngine
    @Inject lateinit var profileRepository: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            val settings = active?.eq ?: EqSettings()
            val next = settings.copy(enabled = !settings.enabled)
            profileEngine.applyEqualizerOnly(next)
            active?.let { profileRepository.save(it.copy(eq = next)) }
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            val enabled = active?.eq?.enabled == true
            qsTile?.apply {
                state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = getString(R.string.tile_equalizer)
                subtitle = if (enabled) active?.eq?.mode?.displayName ?: "On" else "Off"
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Cycles through saved profiles. */
@AndroidEntryPoint
class ProfileTileService : TileService() {

    @Inject lateinit var profileEngine: ProfileEngine
    @Inject lateinit var profileRepository: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val profiles = profileRepository.getAll().sortedBy { it.name }
            if (profiles.isEmpty()) return@launch
            val activeIndex = profiles.indexOfFirst { it.isActive }
            val next = profiles[(activeIndex + 1).mod(profiles.size)]
            profileEngine.apply(next)
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            qsTile?.apply {
                state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = getString(R.string.tile_audio_profile)
                subtitle = active?.name ?: "None"
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Shows the current output and opens the app to switch it. */
@AndroidEntryPoint
class OutputSwitchTileService : TileService() {

    @Inject lateinit var router: AudioRouter

    override fun onStartListening() {
        super.onStartListening()
        val active = router.activeOutput()
        qsTile?.apply {
            state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_audio_output)
            subtitle = active?.label ?: "Unknown"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Routing choice is a list, not a toggle — open the app rather than guessing.
        val intent = android.content.Intent(this, com.soniccore.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
