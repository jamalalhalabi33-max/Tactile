package com.example.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object TactileHaptics {

    fun dpadTick(view: View) {
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            HapticFeedbackConstants.KEYBOARD_TAP
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    fun okClick(view: View) {
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    fun backDoubleTick(view: View, scope: CoroutineScope) {
        val feedback = HapticFeedbackConstants.KEYBOARD_TAP
        view.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        scope.launch(Dispatchers.Main) {
            delay(75)
            view.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
    }

    fun volumeTick(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    fun muteHeavyClick(view: View) {
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }
}
