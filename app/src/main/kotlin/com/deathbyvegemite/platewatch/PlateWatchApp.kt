package com.deathbyvegemite.platewatch

import android.app.Application
import com.deathbyvegemite.platewatch.di.AppContainer
import com.deathbyvegemite.platewatch.work.RetentionWorker

class PlateWatchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        RetentionWorker.schedule(this)
    }
}
