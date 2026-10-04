package com.bitnesttechs.hms.patient.core.network

import com.bitnesttechs.hms.patient.BuildConfig
import android.content.Context
import com.bitnesttechs.hms.patient.core.auth.AuthInterceptor
import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }

    @Provides
    @Singleton
    fun provideAcceptLanguageInterceptor(
        @ApplicationContext context: Context
    ): AcceptLanguageInterceptor = AcceptLanguageInterceptor { LocaleHelper.getLanguage(context) }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        acceptLanguage: AcceptLanguageInterceptor,
        logging: HttpLoggingInterceptor,
        cookieJar: SessionCookieJar
    ): OkHttpClient {

        // Interceptor that reads XSRF-TOKEN cookie and sends it as X-XSRF-TOKEN header
        val csrfInterceptor = Interceptor { chain ->
            val request = chain.request()
            val method = request.method.uppercase()
            if (method in listOf("POST", "PUT", "DELETE", "PATCH")) {
                val cookies = cookieJar.loadForRequest(request.url)
                val xsrf = cookies.firstOrNull { it.name == SessionCookieJar.XSRF_COOKIE }
                if (xsrf != null) {
                    val newReq = request.newBuilder()
                        .header(SessionCookieJar.XSRF_HEADER, xsrf.value)
                        .build()
                    return@Interceptor chain.proceed(newReq)
                }
            }
            chain.proceed(request)
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(acceptLanguage)
            .addInterceptor(authInterceptor)
            .addInterceptor(csrfInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL + "/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}
