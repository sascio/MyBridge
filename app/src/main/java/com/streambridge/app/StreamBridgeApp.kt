package com.streambridge.app

import android.app.Application
import com.streambridge.app.di.AppContainer

class StreamBridgeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
