package com.example.bledfutesteractivity

import android.app.Activity
import no.nordicsemi.android.dfu.DfuBaseService

class DfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java
    override fun isDebug() = true
}
