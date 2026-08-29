package com.example.vescurus

import android.app.Application

/**
 * Application entry point. Owns the [AppContainer] — the app's manual DI
 * root. ViewModels and repositories reach the container via
 * `(application as VescurusApplication).container`.
 */
class VescurusApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
