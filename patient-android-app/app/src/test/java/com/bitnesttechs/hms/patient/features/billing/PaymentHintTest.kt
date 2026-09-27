package com.bitnesttechs.hms.patient.features.billing

import com.bitnesttechs.hms.patient.StringsXml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend now stores the payment method, the transaction reference and
 * the notes with the amount, so the form no longer tells the patient that
 * only the amount is kept.
 */
class PaymentHintTest {

    @Test
    fun `the payment hint no longer says only the amount is stored`() {
        val en = StringsXml.read("values").getValue("payment_sheet_hint")
        val fr = StringsXml.read("values-fr").getValue("payment_sheet_hint")
        assertFalse(en, en.contains("only the amount", ignoreCase = true))
        assertFalse(fr, fr.contains("seul le montant", ignoreCase = true))
        // Still names the invoice and the balance.
        assertTrue(en.contains("%1\$s") && en.contains("%2\$s"))
        assertTrue(fr.contains("%1\$s") && fr.contains("%2\$s"))
    }
}
