package com.bitnesttechs.hms.patient

import android.app.Application
import android.content.Context
import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
import com.bitnesttechs.hms.patient.core.network.AppText
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MediHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
    }
}
