package com.bitnesttechs.hms.patient

import android.content.Context
import android.content.Intent
import com.bitnesttechs.hms.patient.core.push.PushNavigation
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
        // A tapped chat notification (drawn by the app or by the system).
        if (savedInstanceState == null) PushNavigation.offer(intent)
        enableEdgeToEdge()
        setContent {
            MediHubTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PushNavigation.offer(intent)
    }
}
