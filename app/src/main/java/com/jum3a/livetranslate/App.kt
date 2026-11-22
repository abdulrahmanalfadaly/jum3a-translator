package com.jum3a.livetranslate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.jum3a.livetranslate.ai.GroqClient
import com.jum3a.livetranslate.session.Engine
import com.jum3a.livetranslate.settings.SettingsStore
import com.jum3a.livetranslate.update.UpdateManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        val channel = NotificationChannel(
            CHANNEL_LIVE,
            "Live translation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the microphone is listening and translating" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_LIVE = "live_translation"
    }
}

/** Tiny service locator: one instance of each long-lived component for the whole app process. */
object Graph {
    lateinit var app: Application
        private set

    fun init(app: Application) {
        this.app = app
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val groq: GroqClient by lazy { GroqClient(http) }
    val engine: Engine by lazy { Engine(app, settings, groq) }
    val updates: UpdateManager by lazy { UpdateManager(app, http) }
}
