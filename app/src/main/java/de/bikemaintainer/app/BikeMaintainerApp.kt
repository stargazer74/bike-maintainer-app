package de.bikemaintainer.app

import android.app.Application

class BikeMaintainerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
