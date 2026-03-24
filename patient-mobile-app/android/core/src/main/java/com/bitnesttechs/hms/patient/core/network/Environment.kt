package com.bitnesttechs.hms.patient.core.network

/** API environment configuration */
enum class Environment(val baseUrl: String) {
    DEV("https://hms.dev.bitnesttechs.com/api/"),
    UAT("https://hms.uat.bitnesttechs.com/api/"),
    PROD("https://hms.bitnesttechs.com/api/");

    companion object {
        val current: Environment
            get() = if (com.bitnesttechs.hms.patient.core.BuildConfig.DEBUG) DEV else PROD
    }
}
