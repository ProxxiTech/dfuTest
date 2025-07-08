package com.example.bledfutesteractivity

import android.app.Activity
import no.nordicsemi.android.dfu.DfuBaseService

/**
 * This service is required by the Nordic DFU Library.
 * It runs in the background and handles the low-level DFU protocol.
 * The DfuTestingService starts this service for each DFU attempt.
 */
class DfuService : DfuBaseService() {

    override fun getNotificationTarget(): Class<out Activity> {
        // This activity will be opened when the DFU notification is tapped.
        return MainActivity::class.java
    }

    override fun isDebug(): Boolean {
        // Set to true to show detailed DFU debug logs in Logcat.
        // It's helpful for development and should be set to false for release.
        return true
    }
}