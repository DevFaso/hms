package com.example.hms.service.timeline;

import com.example.hms.enums.EncounterType;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Encounter;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.PatientTimelineEntryDTO;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D959: after a full consultation the chronology lacked the diagnoses
 * (B50.9, J18.9), the lab and X-ray orders still waiting, the signed note
 * with its plan and the referrals.
 */
class VisitTimelineRowsTest {

    private final PatientProblemRepository problems = mock(PatientProblemRepository.class);
    private final LabOrderRepository labOrders = mock(LabOrderRepository.class);
    private final ImagingOrderRepository imagingOrders = mock(ImagingOrderRepository.class);
    private final GeneralReferralRepository referrals = mock(GeneralReferralRepository.class);
    private final EncounterRepository encounters = mock(EncounterRepository.class);
    private final EncounterNoteRepository notes = mock(EncounterNoteRepository.class);
    private final VisitTimelineRows rows =
        new VisitTimelineRows(problems, labOrders, imagingOrders, referrals, encounters, notes);

    private final UUID patientId = UUID.randomUUID();
    private final Hospital here = hospital();
    private MessageSource previous;

    private static Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setName("CHU QA");
        return h;
    }

    @BeforeEach
    void frenchBundles() {
        previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void restore() {
        LocaleContextHolder.resetLocaleContext();
        MessageUtil.setMessageSource(previous);
    }

    private void aFullConsultation() {
        PatientProblem malaria = new PatientProblem();
        malaria.setId(UUID.randomUUID());
        malaria.setHospital(here);
        malaria.setProblemCode("B50.9");
        malaria.setProblemDisplay("Paludisme à Plasmodium falciparum");
        malaria.setStatus(ProblemStatus.ACTIVE);
        malaria.setCreatedAt(LocalDateTime.now().minusHours(3));
        when(problems.findByPatient_IdAndHospital_Id(patientId, here.getId())).thenReturn(List.of(malaria));

        LabTestDefinition nfs = new LabTestDefinition();
        nfs.setName("NFS");
        LabOrder waiting = LabOrder.builder().hospital(here).labTestDefinition(nfs).status(LabOrderStatus.ORDERED)
            .orderDatetime(LocalDateTime.now().minusHours(2)).build();
        waiting.setId(UUID.randomUUID());
        LabOrder resulted = LabOrder.builder().hospital(here).labTestDefinition(nfs).status(LabOrderStatus.VERIFIED)
            .orderDatetime(LocalDateTime.now().minusHours(4)).build();
        resulted.setId(UUID.randomUUID());
        when(labOrders.findByPatient_IdAndHospital_Id(patientId, here.getId())).thenReturn(List.of(waiting, resulted));

        ImagingOrder xray = new ImagingOrder();
        xray.setId(UUID.randomUUID());
        xray.setHospital(here);
        xray.setModality(ImagingModality.XRAY);
        xray.setBodyRegion("Thorax");
        xray.setStatus(ImagingOrderStatus.ORDERED);
        xray.setOrderedAt(LocalDateTime.now().minusHours(2));
        when(imagingOrders.findByPatient_IdAndHospital_Id(patientId, here.getId())).thenReturn(List.of(xray));

        Encounter visit = Encounter.builder().hospital(here).encounterType(EncounterType.OUTPATIENT).build();
        visit.setId(UUID.randomUUID());
        when(encounters.findByPatient_Id(patientId)).thenReturn(List.of(visit));
        EncounterNote signed = new EncounterNote();
        signed.setId(UUID.randomUUID());
        signed.setHospital(here);
        signed.setAssessment("Paludisme grave probable");
        signed.setPlan("Artésunate IV, contrôle TDR à J3");
        signed.setSignedAt(LocalDateTime.now().minusHours(1));
        signed.setSignedByName("Dr Ouédraogo");
        EncounterNote draft = new EncounterNote();
        draft.setId(UUID.randomUUID());
        draft.setHospital(here);
        draft.setAssessment("brouillon");
        when(notes.findByEncounter_IdIn(List.of(visit.getId()))).thenReturn(List.of(signed, draft));

        GeneralReferral cardio = new GeneralReferral();
        cardio.setId(UUID.randomUUID());
        cardio.setHospital(here);
        cardio.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        cardio.setStatus(ReferralStatus.SUBMITTED);
        cardio.setReferralReason("Souffle systolique");
        cardio.setSubmittedAt(LocalDateTime.now().minusMinutes(30));
        when(referrals.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, here.getId()))
            .thenReturn(List.of(cardio));
    }

    @Test
    void everyClinicalStepOfTheVisitIsOnTheChronology() {
        aFullConsultation();

        List<PatientTimelineEntryDTO> visit = rows.collect(patientId, here.getId(), Set.of());

        assertThat(visit).extracting(PatientTimelineEntryDTO::getCategory)
            .containsExactlyInAnyOrder("DIAGNOSIS", "LAB_ORDER", "IMAGING", "NOTE", "REFERRAL");
        assertThat(visit).extracting(PatientTimelineEntryDTO::getSummary).containsExactlyInAnyOrder(
            "B50.9 — Paludisme à Plasmodium falciparum (Actif)",
            "NFS : demandée, résultat en attente",
            "Radiographie · Thorax : Prescrit",
            "Note signée — Paludisme grave probable · Plan : Artésunate IV, contrôle TDR à J3",
            "Orientation vers Cardiologie (Envoyée) — Souffle systolique");
        assertThat(visit).allSatisfy(row -> assertThat(row.getMetadata()).containsEntry("foreign", false));
    }

    @Test
    void aCategoryFilterReadsOnlyThatCategory() {
        aFullConsultation();

        List<PatientTimelineEntryDTO> only = rows.collect(patientId, here.getId(), Set.of("DIAGNOSIS"));

        assertThat(only).extracting(PatientTimelineEntryDTO::getCategory).containsExactly("DIAGNOSIS");
        verify(labOrders, never()).findByPatient_IdAndHospital_Id(patientId, here.getId());
        verify(notes, never()).findByEncounter_IdIn(anyCollection());
    }

    /**
     * Guard for D996: every constant the chronology prints has its own French
     * and Spanish label, so no row reads "INPATIENT" or "Submitted" again.
     */
    @Test
    void everyConstantTheChronologyPrintsHasItsOwnFrenchAndSpanishLabel() {
        List<Enum<?>> printed = new ArrayList<>();
        for (Class<? extends Enum<?>> type : List.<Class<? extends Enum<?>>>of(EncounterType.class,
                ImagingModality.class, ImagingOrderStatus.class, ReferralStatus.class, ReferralSpecialty.class,
                UltrasoundScanType.class)) {
            printed.addAll(List.of(type.getEnumConstants()));
        }
        List<String> missing = new ArrayList<>();
        for (Enum<?> value : printed) {
            String en = labelIn(value, Locale.ENGLISH);
            String fr = labelIn(value, Locale.FRENCH);
            String es = labelIn(value, Locale.forLanguageTag("es"));
            if (fr == null || es == null || (fr.equals(en) && es.equals(en))) {
                missing.add(value.getDeclaringClass().getSimpleName() + "." + value.name());
            }
        }
        assertThat(missing).as("constants the chronology would print untranslated").isEmpty();
    }

    private static String labelIn(Enum<?> value, Locale locale) {
        MessageSource bundles = TestMessageSources.bundles();
        String own = bundles.getMessage("timeline.enum." + value.name(), null, null, locale);
        return own != null ? own : bundles.getMessage("pdf.record.enum." + value.name(), null, null, locale);
    }
}
