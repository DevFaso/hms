package com.bitnesttechs.hms.patient.core.locale

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    private const val PREF_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    /** Supported language codes */
    val supportedLanguages = listOf("en", "fr")

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "en") ?: "en"
    }

    fun setLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    fun getDisplayName(languageCode: String): String {
        return when (languageCode) {
            "fr" -> "Français"
            else -> "English"
        }
    }

    fun translateProviderDescriptor(context: Context, value: String?): String? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        if (getLanguage(context) != "fr") return raw

        val key = raw.trim()
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')

        return frenchProviderDescriptors[key] ?: raw
    }

    fun applyLocale(context: Context): Context {
        val lang = getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private val frenchProviderDescriptors = mapOf(
        "DOCTOR" to "Médecin",
        "PHYSICIAN" to "Médecin",
        "PRIMARY_PHYSICIAN" to "Médecin traitant",
        "NURSE" to "Infirmier",
        "SPECIALIST" to "Spécialiste",
        "SPECIALTY" to "Spécialité",
        "SPECIALITY" to "Spécialité",
        "PROVIDER" to "Prestataire",
        "GENERAL_MEDICINE" to "Médecine générale",
        "FAMILY_MEDICINE" to "Médecine familiale",
        "INTERNAL_MEDICINE" to "Médecine interne",
        "CARDIOLOGY" to "Cardiologie",
        "DERMATOLOGY" to "Dermatologie",
        "PEDIATRICS" to "Pédiatrie",
        "OBSTETRICS_GYNECOLOGY" to "Obstétrique et gynécologie",
        "OBSTETRICS_AND_GYNECOLOGY" to "Obstétrique et gynécologie",
        "GYNECOLOGY" to "Gynécologie",
        "ORTHOPEDICS" to "Orthopédie",
        "ORTHOPAEDICS" to "Orthopédie",
        "NEUROLOGY" to "Neurologie",
        "PSYCHIATRY" to "Psychiatrie",
        "RADIOLOGY" to "Radiologie",
        "ANESTHESIOLOGY" to "Anesthésiologie",
        "EMERGENCY_MEDICINE" to "Médecine d'urgence",
        "ONCOLOGY" to "Oncologie",
        "OPHTHALMOLOGY" to "Ophtalmologie",
        "ENT" to "ORL",
        "OTOLARYNGOLOGY" to "ORL",
        "UROLOGY" to "Urologie",
        "NEPHROLOGY" to "Néphrologie",
        "GASTROENTEROLOGY" to "Gastro-entérologie",
        "ENDOCRINOLOGY" to "Endocrinologie",
        "PULMONOLOGY" to "Pneumologie",
        "RESPIRATORY_MEDICINE" to "Pneumologie",
        "RHEUMATOLOGY" to "Rhumatologie",
        "HEMATOLOGY" to "Hématologie",
        "INFECTIOUS_DISEASE" to "Maladies infectieuses",
        "PATHOLOGY" to "Anatomopathologie",
        "SURGERY" to "Chirurgie",
        "GENERAL_SURGERY" to "Chirurgie générale",
        "DENTISTRY" to "Dentisterie"
    )
}
