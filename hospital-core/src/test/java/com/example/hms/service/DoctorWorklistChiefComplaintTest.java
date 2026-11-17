package com.example.hms.service;

import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D238: the worklist read the encounter's notes as its chief complaint. */
class DoctorWorklistChiefComplaintTest {

    @Test
    void theEncountersChiefComplaintIsShownNotItsNotes() {
        Encounter enc = new Encounter();
        enc.setChiefComplaint("Fièvre depuis 3 jours");
        enc.setNotes(null);
        assertThat(DoctorWorklistServiceImpl.chiefComplaintOf(enc)).isEqualTo("Fièvre depuis 3 jours");
    }

    @Test
    void theBookedReasonFillsAnEmptyChiefComplaint() {
        Encounter enc = new Encounter();
        enc.setNotes("internal note");
        Appointment appt = new Appointment();
        appt.setReason("Contrôle tension");
        enc.setAppointment(appt);
        assertThat(DoctorWorklistServiceImpl.chiefComplaintOf(enc)).isEqualTo("Contrôle tension");
    }
}
