package com.bitnesttechs.hms.patient.core.localization

import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app locale for multilingual support.
 * Supports runtime language switching.
 */
@Singleton
class LocaleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val supportedLanguages = listOf(
            LanguageOption("en", "English"),
            LanguageOption("fr", "Français"),
            LanguageOption("es", "Español"),
            LanguageOption("pt", "Português"),
            LanguageOption("sw", "Kiswahili"),
            LanguageOption("ar", "العربية"),
            LanguageOption("zh", "中文")
        )
    }

    private val prefs = context.getSharedPreferences("hms_settings", Context.MODE_PRIVATE)

    var currentLanguage: String
        get() = prefs.getString("app_language", Locale.getDefault().language) ?: "en"
        set(value) {
            prefs.edit().putString("app_language", value).apply()
        }

    /**
     * Apply the saved locale to a Context (call from attachBaseContext).
     */
    fun applyLocale(baseContext: Context): Context {
        val locale = Locale(currentLanguage)
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration).apply {
            setLocale(locale)
        }
        return baseContext.createConfigurationContext(config)
    }
}

data class LanguageOption(
    val code: String,
    val displayName: String
)
