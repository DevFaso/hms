package com.example.hms.service;

import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D239: the reception queue's reason column. */
class ReceptionVisitReasonTest {

    @Test
    void theBookedReasonComesFirst() {
        Appointment appt = new Appointment();
        appt.setReason("Douleur abdominale");
        assertThat(ReceptionServiceImpl.visitReason(appt, null)).isEqualTo("Douleur abdominale");
    }

    @Test
    void withNoBookedReasonTheTriageComplaintIsShown() {
        Appointment appt = new Appointment();
        Encounter enc = new Encounter();
        enc.setChiefComplaint("Toux");
        assertThat(ReceptionServiceImpl.visitReason(appt, enc)).isEqualTo("Toux");
        // RCPT-CLIN: the same row for the desk: triage's complaint is not on it.
        assertThat(ReceptionServiceImpl.deskVisitReason(appt, enc)).isNull();
        enc.setDeskChiefComplaint("Fièvre");
        assertThat(ReceptionServiceImpl.deskVisitReason(appt, enc)).isEqualTo("Fièvre");
    }
}
