package dev.c0redev.pteraandroid.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.c0redev.pteraandroid.MainActivity
import dev.c0redev.pteraandroid.R

abstract class QuickConnectTileServiceBase : TileService() {

    protected abstract val slotIndex: Int

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val qs = qsTile ?: return
        val name = QuickConnectPrefs.getSlotName(this, slotIndex)
        qs.label = when (slotIndex) {
            0 -> getString(R.string.quick_slot_1)
            1 -> getString(R.string.quick_slot_2)
            else -> getString(R.string.quick_slot_3)
        }
        qs.subtitle = name ?: getString(R.string.quick_tile_empty)
        qs.state = if (name.isNullOrBlank()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        qs.updateTile()
    }

    override fun onClick() {
        val launch = Runnable {
            val name = QuickConnectPrefs.getSlotName(this, slotIndex)?.trim().orEmpty()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (name.isNotEmpty()) {
                    putExtra(MainActivity.EXTRA_QUICK_PROFILE, name)
                } else {
                    putExtra(MainActivity.EXTRA_OPEN_SETTINGS_QUICK, true)
                }
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this,
                    0x7000 + slotIndex,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) {
            unlockAndRun(launch)
        } else {
            launch.run()
        }
    }
}

class QuickConnectTile1 : QuickConnectTileServiceBase() {
    override val slotIndex = 0
}

class QuickConnectTile2 : QuickConnectTileServiceBase() {
    override val slotIndex = 1
}

class QuickConnectTile3 : QuickConnectTileServiceBase() {
    override val slotIndex = 2
}
