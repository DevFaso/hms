package com.bitnesttechs.hms.patient.documents.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class DocumentsRepository @Inject constructor(
    private val api: DocumentsApi
) {
    suspend fun getDocuments(page: Int, size: Int) = api.getDocuments(page, size)
}

@Module
@InstallIn(SingletonComponent::class)
object DocumentsModule {
    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): DocumentsApi =
        retrofit.create(DocumentsApi::class.java)
}
