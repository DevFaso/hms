package com.bitnesttechs.hms.patient.features.messages

import android.content.Context
import com.bitnesttechs.hms.patient.core.models.ChatAttachmentDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.File

/** Attachments come through the authenticated download, once, into the app-private cache. */
class ChatAttachmentCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val api = mockk<ApiService>()
    private fun cache(): Pair<ChatAttachmentCache, File> {
        val cacheDir = tmp.newFolder("cache")
        val context = mockk<Context> { every { this@mockk.cacheDir } returns cacheDir }
        return ChatAttachmentCache(context, api) to cacheDir
    }

    private val photo = ChatAttachmentDto(id = "3f2a-77", contentType = "image/jpeg", kind = "PHOTO", sizeBytes = 3)

    @Test
    fun `downloads once through the authenticated endpoint, then serves the cached file`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { api.downloadChatAttachment("3f2a-77") } returns
            Response.success(bytes.toResponseBody("image/jpeg".toMediaType()))
        val (cache, cacheDir) = cache()

        val first = cache.file(photo)!!
        val second = cache.file(photo)!!

        assertEquals(File(cacheDir, "chat_attachments/3f2a-77.jpg"), first)
        assertEquals(first, second)
        assertArrayEquals(bytes, first.readBytes())
        coVerify(exactly = 1) { api.downloadChatAttachment("3f2a-77") }
    }

    @Test
    fun `a refused download leaves nothing behind and reports no file`() = runTest {
        coEvery { api.downloadChatAttachment(any()) } returns
            Response.error(403, "{}".toResponseBody("application/json".toMediaType()))
        val (cache, cacheDir) = cache()

        assertNull(cache.file(photo))
        assertFalse(File(cacheDir, "chat_attachments/3f2a-77.jpg").exists())
    }

    @Test
    fun `file names come from the id and the content type, never the sender's display name`() {
        assertEquals("abc-1.m4a", ChatAttachmentCache.fileName(ChatAttachmentDto(id = "abc-1", contentType = "audio/mp4", displayName = "../../x.sh")))
        assertEquals("abc1.bin", ChatAttachmentCache.fileName(ChatAttachmentDto(id = "a/b/c/1", contentType = "application/x-unknown")))
        assertEquals("id.png", ChatAttachmentCache.fileName(ChatAttachmentDto(id = "id", contentType = "image/png; charset=binary")))
    }
}
