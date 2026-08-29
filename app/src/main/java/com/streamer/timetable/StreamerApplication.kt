package com.streamer.timetable

import android.app.Application
import com.streamer.timetable.debug.CrashReporter

class StreamerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed first, so a crash during the rest of startup is still captured.
        CrashReporter.install(this)
    }
}
