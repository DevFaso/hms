package com.bitnesttechs.hms.patient.core.network

import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.StringsXml
import com.squareup.moshi.JsonDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The detail appended to a failed request's headline used to be
 * `e.message` ("Unable to resolve host "dev.e-keneya.com": No address
 * associated with hostname") or "HTTP 500": English inside a French screen.
 * It is now always one of the app's own resources.
 */
class FailureTextTest {

    @Test
    fun `transport failures map to the app's own sentences`() {
        assertEquals(R.string.error_network, FailureText.resFor(UnknownHostException("dev.e-keneya.com")))
        assertEquals(R.string.error_network, FailureText.resFor(ConnectException("refused")))
        assertEquals(R.string.error_timeout, FailureText.resFor(SocketTimeoutException("timeout")))
        assertEquals(R.string.error_network, FailureText.resFor(IOException("reset")))
        assertEquals(R.string.error_generic, FailureText.resFor(JsonDataException("bad json")))
    }

    @Test
    fun `HTTP statuses map to a sentence, never to the bare code`() {
        assertEquals(R.string.error_session_expired, FailureText.resForStatus(401))
        assertEquals(R.string.error_forbidden, FailureText.resForStatus(403))
        assertEquals(R.string.error_not_found, FailureText.resForStatus(404))
        assertEquals(R.string.error_server, FailureText.resForStatus(500))
        assertEquals(R.string.error_server, FailureText.resForStatus(503))
        assertEquals(R.string.error_request_refused, FailureText.resForStatus(409))
    }

    @Test
    fun `every failure sentence is translated`() {
        val en = StringsXml.read("values")
        val fr = StringsXml.read("values-fr")
        for (name in listOf("error_network", "error_timeout", "error_session_expired", "error_forbidden",
            "error_not_found", "error_server", "error_request_refused", "error_generic")) {
            assertFalse("$name missing in en", en[name].isNullOrBlank())
            assertFalse("$name missing in fr", fr[name].isNullOrBlank())
            assertFalse("$name is untranslated in fr", en[name] == fr[name])
        }
    }
}
