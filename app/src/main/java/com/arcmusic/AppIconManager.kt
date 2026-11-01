package com.arcmusic

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.arcmusic.data.AppTheme

/** Launcher aliases declared in the manifest, one per accent theme. */
private enum class LauncherIcon(val alias: String) {
    EMERALD("IconEmerald"),
    OCEAN("IconOcean"),
    VIOLET("IconViolet"),
    SUNSET("IconSunset"),
    CRIMSON("IconCrimson"),
    GRAPHITE("IconGraphite"),
    P3R("IconP3r"),
    CELESTIAL("IconCelestial"),
}

private fun AppTheme.launcherIcon(): LauncherIcon = when (this) {
    AppTheme.EMERALD -> LauncherIcon.EMERALD
    AppTheme.OCEAN -> LauncherIcon.OCEAN
    AppTheme.VIOLET -> LauncherIcon.VIOLET
    AppTheme.SUNSET -> LauncherIcon.SUNSET
    AppTheme.CRIMSON -> LauncherIcon.CRIMSON
    AppTheme.GRAPHITE -> LauncherIcon.GRAPHITE
    AppTheme.P3R -> LauncherIcon.P3R
    AppTheme.CELESTIAL -> LauncherIcon.CELESTIAL
    AppTheme.CUSTOM -> LauncherIcon.CRIMSON
}

/**
 * Keeps the home-screen icon in step with the accent colour.
 *
 * The manifest declares one activity-alias per icon and exactly one is enabled. The
 * target is enabled before the others are disabled, so the launcher never briefly sees
 * zero entries for the app. Package manager writes are skipped when the state already
 * matches, which keeps app start cheap.
 */
object AppIconManager {

    fun apply(context: Context, theme: AppTheme) {
        val target = theme.launcherIcon()
        val manager = context.packageManager
        val packageName = context.packageName

        // The target first, then the rest; see the class note.
        LauncherIcon.entries.sortedBy { it != target }.forEach { icon ->
            val component = ComponentName(packageName, "$packageName.${icon.alias}")
            val desired = if (icon == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val current = runCatching {
                manager.getComponentEnabledSetting(component)
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (current != desired) {
                runCatching {
                    manager.setComponentEnabledSetting(
                        component,
                        desired,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }
    }
}
