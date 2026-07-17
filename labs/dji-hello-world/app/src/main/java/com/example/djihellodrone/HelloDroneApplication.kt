package com.example.djihellodrone

import android.app.Application
import android.content.Context

class HelloDroneApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Mandatory MSDK v5 class-protector bootstrap; the SDK crashes without it.
        com.cySdkyc.clx.Helper.install(this)
    }
}
