package com.bitnesttechs.hms.patient

import android.app.Application
import android.content.Context
import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MediHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }
}
