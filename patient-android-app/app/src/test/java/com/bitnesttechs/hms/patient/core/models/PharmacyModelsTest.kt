package com.bitnesttechs.hms.patient.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

class PharmacyModelsTest {
    @Test
    fun pharmacyPaymentDisplayFormatsMethod() {
        val payment = PharmacyPaymentDto(paymentMethod = "MOBILE_MONEY")

        assertEquals("Mobile money", payment.methodDisplay)
    }

    @Test
    fun pharmacyClaimDisplayFormatsStatus() {
        val claim = PharmacyClaimDto(claimStatus = "SUBMITTED_FOR_REVIEW")

        assertEquals("Submitted for review", claim.statusDisplay)
    }
}
