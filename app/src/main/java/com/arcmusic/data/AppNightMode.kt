package com.arcmusic.data

import androidx.annotation.StringRes
import com.arcmusic.R

/**
 * Which light/dark palette the app draws, independent of the phone's own setting.
 *
 * [SYSTEM] keeps following Android, which is what most people expect until they say
 * otherwise; the other two let the app stay dark on a bright phone, or the reverse.
 */
enum class AppNightMode(@param:StringRes val labelRes: Int) {
    SYSTEM(R.string.night_mode_system),
    LIGHT(R.string.night_mode_light),
    DARK(R.string.night_mode_dark),
}
