package com.bitnesttechs.hms.patient

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
import com.bitnesttechs.hms.patient.navigation.AppNavigation
import com.bitnesttechs.hms.patient.ui.theme.MediHubTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
// FragmentActivity, not ComponentActivity: BiometricPrompt casts the host
// activity to FragmentActivity, and the cast threw on every biometric sign-in.
class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediHubTheme {
                AppNavigation()
            }
        }
    }
}
