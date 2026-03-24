package com.bitnesttechs.hms.patient.billing.data

import com.bitnesttechs.hms.patient.core.network.PageResponse
import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class BillingRepository @Inject constructor(
    private val api: BillingApi
) {
    suspend fun getInvoices(page: Int = 0, size: Int = 20): Result<PageResponse<InvoiceDto>> = try {
        Result.Success(api.getInvoices(page, size))
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load invoices")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    @Provides
    @Singleton
    fun provideBillingApi(retrofit: Retrofit): BillingApi =
        retrofit.create(BillingApi::class.java)
}
