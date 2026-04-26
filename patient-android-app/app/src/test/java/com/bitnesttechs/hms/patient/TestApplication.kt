package com.bitnesttechs.hms.patient

import android.app.Application

/**
 * Plain `Application` used by Robolectric in JVM unit tests so the test
 * runtime does not try to bring up the production `@HiltAndroidApp`
 * `MediHubApplication` (which would require `HiltAndroidRule`).
 *
 * Tests select this class via `@Config(application = TestApplication::class)`.
 */
class TestApplication : Application()
