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
    private final com.example.hms.service.recordaccess.SensitivityClassifier classifier =
        mock(com.example.hms.service.recordaccess.SensitivityClassifier.class);
    private final com.example.hms.repository.TreatmentPlanRepository plans =
        mock(com.example.hms.repository.TreatmentPlanRepository.class);
    private final com.example.hms.repository.ConsultationRepository consultations =
        mock(com.example.hms.repository.ConsultationRepository.class);
    private final com.example.hms.repository.DischargeSummaryRepository dischargeSummaries =
        mock(com.example.hms.repository.DischargeSummaryRepository.class);
    private final com.example.hms.repository.PatientUploadedDocumentRepository documents =
        mock(com.example.hms.repository.PatientUploadedDocumentRepository.class);
    // QA D959 (round 18): the rest of the registry's sources.
    private final com.example.hms.repository.TransfusionRequestRepository transfusions =
        mock(com.example.hms.repository.TransfusionRequestRepository.class);
    private final com.example.hms.repository.ProcedureOrderRepository procedureOrders =
        mock(com.example.hms.repository.ProcedureOrderRepository.class);
    private final com.example.hms.repository.ObgynReferralRepository obgynReferrals =
        mock(com.example.hms.repository.ObgynReferralRepository.class);
    private final com.example.hms.repository.NursingNoteRepository nursingNotes =
        mock(com.example.hms.repository.NursingNoteRepository.class);
    private final com.example.hms.repository.ImmunizationRepository immunizations =
        mock(com.example.hms.repository.ImmunizationRepository.class);
    private final com.example.hms.repository.DeliveryRecordRepository deliveries =
        mock(com.example.hms.repository.DeliveryRecordRepository.class);
    private final com.example.hms.repository.DeathRecordRepository deaths =
        mock(com.example.hms.repository.DeathRecordRepository.class);
    private final com.example.hms.repository.MicroCultureResultRepository cultures =
        mock(com.example.hms.repository.MicroCultureResultRepository.class);
    private final VisitTimelineRows rows =
        new VisitTimelineRows(problems, labOrders, imagingOrders, referrals, encounters, notes,
            plans, consultations, dischargeSummaries, documents, transfusions, procedureOrders, obgynReferrals,
            nursingNotes, immunizations, deliveries, deaths, cultures, classifier);

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
        when(encounters.findByPatient_IdAndHospital_Id(patientId, here.getId())).thenReturn(List.of(visit));
        EncounterNote signed = new EncounterNote();
        signed.setId(UUID.randomUUID());
        signed.setHospital(here);
        signed.setAssessment("Paludisme grave probable");
        signed.setPlan("Artésunate IV, contrôle TDR à J3");
        signed.setSignedAt(LocalDateTime.now().minusHours(1));
        signed.setSignedByName("Dr Ouédraogo");
        signed.setEncounter(visit);
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
        assertThat(visit)
            .allSatisfy(row -> assertThat(row.getMetadata()).containsEntry("foreign", false))
            .noneMatch(PatientTimelineEntryDTO::isSensitive);
    }

    @Test
    void aNoteAndAnOrderOfASensitiveVisitAreSensitiveRows() {
        aFullConsultation();
        when(classifier.effectiveCategory(org.mockito.ArgumentMatchers.any(Encounter.class)))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);
        when(classifier.effectiveCategory(org.mockito.ArgumentMatchers.any(PatientProblem.class)))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);

        List<PatientTimelineEntryDTO> visit = rows.collect(patientId, here.getId(), Set.of());

        assertThat(visit).filteredOn(PatientTimelineEntryDTO::isSensitive)
            .extracting(PatientTimelineEntryDTO::getCategory)
            .contains("NOTE", "DIAGNOSIS");
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
                UltrasoundScanType.class,
                // QA D959 (round 18): the registry's new rows print these too.
                com.example.hms.enums.BloodProductType.class, com.example.hms.enums.DeliveryMode.class,
                com.example.hms.enums.MicroGrowthResult.class)) {
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

    /**
     * QA D1027 guard: every status a chronology row prints through
     * TimelineStatus has its own French and Spanish label.
     */
    @Test
    void everyStatusTheChronologyPrintsHasItsOwnFrenchAndSpanishLabel() {
        MessageSource bundles = TestMessageSources.bundles();
        List<String> missing = new ArrayList<>();
        for (Class<? extends Enum<?>> type : List.<Class<? extends Enum<?>>>of(
                com.example.hms.enums.PrescriptionStatus.class, com.example.hms.enums.TreatmentPlanStatus.class,
                com.example.hms.enums.ConsultationStatus.class, com.example.hms.enums.EncounterStatus.class,
                com.example.hms.enums.UltrasoundOrderStatus.class,
                // QA D959 (round 18).
                com.example.hms.enums.TransfusionRequestStatus.class, com.example.hms.enums.ProcedureOrderStatus.class,
                com.example.hms.enums.ObgynReferralStatus.class)) {
            for (Enum<?> value : type.getEnumConstants()) {
                String key = "timeline.status." + type.getSimpleName() + "." + value.name();
                String fr = bundles.getMessage(key, null, null, Locale.FRENCH);
                String es = bundles.getMessage(key, null, null, Locale.forLanguageTag("es"));
                if (fr == null || es == null) {
                    missing.add(key);
                }
            }
        }
        assertThat(missing).as("statuses the chronology would print untranslated").isEmpty();
    }

    @Test
    void theVisitDocumentsAreOnTheChronologyWithTheirStatus() {
        // QA D959 (round 17): plan, consultation request, discharge summary, certificate.
        com.example.hms.model.treatment.TreatmentPlan plan = new com.example.hms.model.treatment.TreatmentPlan();
        plan.setId(UUID.randomUUID());
        plan.setHospital(here);
        plan.setProblemStatement("Paludisme grave");
        plan.setStatus(com.example.hms.enums.TreatmentPlanStatus.CANCELLED);
        plan.setCreatedAt(LocalDateTime.now().minusHours(1));
        when(plans.findAllByPatientIdAndHospitalId(org.mockito.ArgumentMatchers.eq(patientId),
            org.mockito.ArgumentMatchers.eq(here.getId()), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(plan)));
        com.example.hms.model.Consultation consult = new com.example.hms.model.Consultation();
        consult.setId(UUID.randomUUID());
        consult.setHospital(here);
        consult.setSpecialtyRequested("Cardiologie");
        consult.setStatus(com.example.hms.enums.ConsultationStatus.REQUESTED);
        when(consultations.findByPatient_IdAndHospital_IdInOrderByRequestedAtDesc(patientId, Set.of(here.getId())))
            .thenReturn(List.of(consult));
        com.example.hms.model.discharge.DischargeSummary discharge = new com.example.hms.model.discharge.DischargeSummary();
        discharge.setId(UUID.randomUUID());
        discharge.setHospital(here);
        discharge.setIsFinalized(true);
        discharge.setDischargeDiagnosis("Pneumonie");
        discharge.setFinalizedAt(LocalDateTime.now().minusMinutes(30));
        when(dischargeSummaries.findByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientId, here.getId()))
            .thenReturn(List.of(discharge));
        com.example.hms.model.PatientUploadedDocument certificate = new com.example.hms.model.PatientUploadedDocument();
        certificate.setId(UUID.randomUUID());
        certificate.setIssuingHospital(here);
        certificate.setDocumentType(com.example.hms.enums.PatientDocumentType.MEDICAL_CERTIFICATE);
        certificate.setDisplayName("Arrêt de travail");
        com.example.hms.model.PatientUploadedDocument upload = new com.example.hms.model.PatientUploadedDocument();
        upload.setId(UUID.randomUUID());
        upload.setDocumentType(com.example.hms.enums.PatientDocumentType.MEDICAL_CERTIFICATE);
        upload.setDisplayName("Le mien");
        when(documents.findForStaffByType(org.mockito.ArgumentMatchers.eq(patientId),
            org.mockito.ArgumentMatchers.eq(here.getId()),
            org.mockito.ArgumentMatchers.eq(com.example.hms.enums.PatientDocumentType.MEDICAL_CERTIFICATE),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(certificate, upload)));

        assertThat(rows.collect(patientId, here.getId(),
                Set.of("TREATMENT_PLAN", "CONSULTATION", "DISCHARGE_SUMMARY", "DOCUMENT")))
            .extracting(PatientTimelineEntryDTO::getSummary)
            .containsExactlyInAnyOrder(
                "Plan de traitement : Paludisme grave (Annulé)",
                "Demande d’avis : Cardiologie (Demandée)",
                "Résumé de sortie (finalisé) : Pneumonie",
                "Certificat médical : Arrêt de travail");
    }

    private static String labelIn(Enum<?> value, Locale locale) {
        MessageSource bundles = TestMessageSources.bundles();
        String own = bundles.getMessage("timeline.enum." + value.name(), null, null, locale);
        return own != null ? own : bundles.getMessage("pdf.record.enum." + value.name(), null, null, locale);
    }

    @Test
    void aRemovedDiagnosisStaysOnTheChronologyWithItsStatus() {
        // Removal sets INACTIVE, as a clinician may: the row stays, as on the chart's history.
        PatientProblem removed = new PatientProblem();
        removed.setId(UUID.randomUUID());
        removed.setHospital(here);
        removed.setProblemCode("J18.9");
        removed.setProblemDisplay("Pneumonie");
        removed.setStatus(ProblemStatus.INACTIVE);
        removed.setCreatedAt(LocalDateTime.now().minusHours(2));
        when(problems.findByPatient_IdAndHospital_Id(patientId, here.getId())).thenReturn(List.of(removed));

        assertThat(rows.collect(patientId, here.getId(), Set.of("DIAGNOSIS")))
            .extracting(PatientTimelineEntryDTO::getSummary).containsExactly("J18.9 — Pneumonie (Inactif)");
    }

    @Test
    void rowsWithLittleOnThemStillRead() {
        PatientProblem codeOnly = new PatientProblem();
        codeOnly.setId(UUID.randomUUID());
        codeOnly.setHospital(here);
        codeOnly.setProblemCode("R50.9");
        codeOnly.setStatus(null); // a legacy row with no status reads its code alone
        codeOnly.setOnsetDate(java.time.LocalDate.of(2026, 10, 20));
        when(problems.findByPatient_IdAndHospital_Id(patientId, here.getId())).thenReturn(List.of(codeOnly));
        GeneralReferral bare = new GeneralReferral();
        bare.setId(UUID.randomUUID());
        bare.setHospital(here);
        bare.setStatus(ReferralStatus.ACKNOWLEDGED);
        when(referrals.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, here.getId()))
            .thenReturn(List.of(bare));
        ImagingOrder noStudy = new ImagingOrder();
        noStudy.setId(UUID.randomUUID());
        noStudy.setHospital(here);
        noStudy.setModality(ImagingModality.CT);
        noStudy.setStatus(ImagingOrderStatus.SCHEDULED);
        when(imagingOrders.findByPatient_IdAndHospital_Id(patientId, here.getId())).thenReturn(List.of(noStudy));

        List<PatientTimelineEntryDTO> visit = rows.collect(patientId, here.getId(),
            Set.of("DIAGNOSIS", "REFERRAL", "IMAGING"));

        assertThat(visit).extracting(PatientTimelineEntryDTO::getSummary).containsExactlyInAnyOrder(
            "R50.9", "Orientation vers — (Prise en compte)", "Scanner : Programmée");
        assertThat(visit).filteredOn(r -> "DIAGNOSIS".equals(r.getCategory())).singleElement()
            .extracting(PatientTimelineEntryDTO::getOccurredAt)
            .isEqualTo(java.time.LocalDate.of(2026, 10, 20).atStartOfDay());
        // A constant no bundle labels is still made readable.
        assertThat(VisitTimelineRows.label(LabOrderStatus.ENTERED_IN_ERROR)).isEqualTo("Entered in error");
    }
    /**
     * QA D959 (round 18, NOT_FIXED twice): transfusion requests, procedure
     * orders and OB/GYN referrals were missing, and the registry hunt found the
     * nursing notes, vaccines, delivery, death and microbiology cultures too.
     * Every source of the registry writes its row, in French, with its status.
     */
    @Test
    void d959_everyRegisteredSourceWritesItsRow() {
        com.example.hms.model.TransfusionRequest transfusion = new com.example.hms.model.TransfusionRequest();
        transfusion.setId(UUID.randomUUID());
        transfusion.setHospital(here);
        transfusion.setProductType(com.example.hms.enums.BloodProductType.PACKED_RED_CELLS);
        transfusion.setUnitsRequested(2);
        transfusion.setStatus(com.example.hms.enums.TransfusionRequestStatus.CANCELLED);
        transfusion.setRequestedAt(LocalDateTime.now().minusHours(5));
        when(transfusions.findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(patientId, here.getId()))
            .thenReturn(List.of(transfusion));

        com.example.hms.model.ProcedureOrder procedure = new com.example.hms.model.ProcedureOrder();
        procedure.setId(UUID.randomUUID());
        procedure.setHospital(here);
        procedure.setProcedureName("Ponction lombaire");
        procedure.setStatus(com.example.hms.enums.ProcedureOrderStatus.SCHEDULED);
        when(procedureOrders.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(patientId, here.getId()))
            .thenReturn(List.of(procedure));

        com.example.hms.model.referral.ObgynReferral obgyn = new com.example.hms.model.referral.ObgynReferral();
        obgyn.setId(UUID.randomUUID());
        obgyn.setHospital(here);
        obgyn.setReferralReason("Pré-éclampsie suspectée");
        obgyn.setStatus(com.example.hms.enums.ObgynReferralStatus.SUBMITTED);
        when(obgynReferrals.findByPatient_IdAndHospital_IdIn(org.mockito.ArgumentMatchers.eq(patientId),
                org.mockito.ArgumentMatchers.eq(Set.of(here.getId())), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(obgyn)));

        com.example.hms.model.NursingNote note = new com.example.hms.model.NursingNote();
        note.setId(UUID.randomUUID());
        note.setHospital(here);
        note.setTemplate(com.example.hms.model.NursingNoteTemplate.DAR);
        note.setDataAssessment("Patient calme");
        note.setDocumentedAt(LocalDateTime.now().minusHours(1));
        when(nursingNotes.findTop50ByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, Set.of(here.getId())))
            .thenReturn(List.of(note));

        com.example.hms.model.PatientImmunization dose = new com.example.hms.model.PatientImmunization();
        dose.setId(UUID.randomUUID());
        dose.setHospital(here);
        dose.setVaccineDisplay("Pentavalent");
        dose.setDoseNumber(2);
        dose.setStatus("COMPLETED");
        dose.setAdministrationDate(java.time.LocalDate.now().minusDays(3));
        com.example.hms.model.PatientImmunization voided = new com.example.hms.model.PatientImmunization();
        voided.setId(UUID.randomUUID());
        voided.setHospital(here);
        voided.setVaccineDisplay("BCG");
        voided.setStatus("ENTERED_IN_ERROR");
        when(immunizations.findByPatient_IdAndHospital_IdOrderByAdministrationDateDesc(patientId, here.getId()))
            .thenReturn(List.of(dose, voided));

        com.example.hms.model.labor.DeliveryRecord delivery = new com.example.hms.model.labor.DeliveryRecord();
        delivery.setId(UUID.randomUUID());
        delivery.setHospital(here);
        delivery.setDeliveryMode(com.example.hms.enums.DeliveryMode.SPONTANEOUS_VAGINAL);
        delivery.setBirthDateTime(LocalDateTime.now().minusDays(1));
        when(deliveries.findByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, here.getId()))
            .thenReturn(List.of(delivery));

        com.example.hms.model.DeathRecord death = new com.example.hms.model.DeathRecord();
        death.setId(UUID.randomUUID());
        death.setHospital(here);
        death.setImmediateCause("Choc septique");
        death.setDiedAt(LocalDateTime.now());
        when(deaths.findByPatient_Id(patientId)).thenReturn(java.util.Optional.of(death));

        com.example.hms.model.MicroCultureResult culture = new com.example.hms.model.MicroCultureResult();
        culture.setId(UUID.randomUUID());
        culture.setHospital(here);
        culture.setSpecimenSource("Hémoculture");
        culture.setGrowthResult(com.example.hms.enums.MicroGrowthResult.NO_GROWTH);
        culture.setStatus(com.example.hms.enums.MicroCultureStatus.CORRECTED);
        culture.setCorrectedAt(LocalDateTime.now().minusHours(2));
        com.example.hms.model.MicroCultureResult preliminary = new com.example.hms.model.MicroCultureResult();
        preliminary.setId(UUID.randomUUID());
        preliminary.setHospital(here);
        preliminary.setStatus(com.example.hms.enums.MicroCultureStatus.PRELIMINARY);
        when(cultures.findForPatient(patientId, here.getId())).thenReturn(List.of(culture, preliminary));

        List<PatientTimelineEntryDTO> visit = rows.collect(patientId, here.getId(), Set.of("TRANSFUSION",
            "PROCEDURE_ORDER", "OBGYN_REFERRAL", "NURSING_NOTE", "IMMUNIZATION", "DELIVERY", "DEATH", "MICRO_CULTURE"));

        assertThat(visit).extracting(PatientTimelineEntryDTO::getCategory, PatientTimelineEntryDTO::getSummary)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("TRANSFUSION",
                    "Demande de transfusion : Concentré de globules rouges, 2 unité(s) (Annulée)"),
                org.assertj.core.groups.Tuple.tuple("PROCEDURE_ORDER", "Demande d’acte : Ponction lombaire (Programmée)"),
                org.assertj.core.groups.Tuple.tuple("OBGYN_REFERRAL",
                    "Orientation gynéco-obstétricale : Pré-éclampsie suspectée (Envoyée)"),
                org.assertj.core.groups.Tuple.tuple("NURSING_NOTE", "Note infirmière (DAR) — Patient calme"),
                org.assertj.core.groups.Tuple.tuple("IMMUNIZATION", "Vaccination : Pentavalent · dose 2 (Administré)"),
                org.assertj.core.groups.Tuple.tuple("DELIVERY", "Accouchement : Voie basse spontanée"),
                org.assertj.core.groups.Tuple.tuple("DEATH", "Décès : Choc septique"),
                org.assertj.core.groups.Tuple.tuple("MICRO_CULTURE", "Culture (Hémoculture) : Culture stérile (Corrigé)"));
        assertThat(visit).allSatisfy(row -> assertThat(row.getMetadata()).containsEntry("foreign", false));
    }

    /** The registry and the published category list are one list: a source cannot be added to one only. */
    @Test
    void d959_theRegistryIsTheCategoryList() {
        assertThat(rows.sources().keySet()).containsExactlyElementsOf(VisitTimelineRows.CATEGORIES);
    }

    /** Another hospital's death record is not this hospital's row. */
    @Test
    void d959_anotherHospitalsDeathRecordIsNotRead() {
        com.example.hms.model.DeathRecord death = new com.example.hms.model.DeathRecord();
        death.setId(UUID.randomUUID());
        death.setHospital(hospital());
        when(deaths.findByPatient_Id(patientId)).thenReturn(java.util.Optional.of(death));

        assertThat(rows.collect(patientId, here.getId(), Set.of("DEATH"))).isEmpty();
    }

    /**
     * QA D1113: a dose transcribed from a vaccination card, another facility's
     * record or the family's word was placed "at" the hospital that typed it in
     * ("… 20 sept. 2021 · domain Centre Hospitalier Wend-Panga"). The row names
     * its source, and the hospital is the one that RECORDED it, not a place it
     * was given. A dose given here keeps its hospital.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "VACCINATION_CARD, d’après le carnet de vaccination",
        "EXTERNAL_RECORD, d’après le dossier d’un autre établissement",
        "PATIENT_REPORT, déclaré par le patient ou la famille",
    })
    void d1113_aTranscribedDoseNamesItsSourceAndIsNotPlacedHere(String source, String label) {
        com.example.hms.model.PatientImmunization dose = new com.example.hms.model.PatientImmunization();
        dose.setId(UUID.randomUUID());
        dose.setHospital(here);
        dose.setVaccineDisplay("BCG");
        dose.setSourceOfRecord(source);
        dose.setAdministrationDate(java.time.LocalDate.of(2021, 9, 20));
        com.example.hms.model.PatientImmunization givenHere = new com.example.hms.model.PatientImmunization();
        givenHere.setId(UUID.randomUUID());
        givenHere.setHospital(here);
        givenHere.setVaccineDisplay("VPO");
        givenHere.setSourceOfRecord("GIVEN_HERE");
        givenHere.setAdministrationDate(java.time.LocalDate.now().minusDays(1));
        when(immunizations.findByPatient_IdAndHospital_IdOrderByAdministrationDateDesc(patientId, here.getId()))
            .thenReturn(List.of(dose, givenHere));

        List<PatientTimelineEntryDTO> visit = rows.collect(patientId, here.getId(), Set.of("IMMUNIZATION"));

        PatientTimelineEntryDTO transcribed = visit.stream()
            .filter(r -> r.getEntryId().equals(dose.getId().toString())).findFirst().orElseThrow();
        assertThat(transcribed.getSummary().replace('\'', '’')).contains("BCG").contains(label);
        assertThat(transcribed.getMetadata())
            .doesNotContainKey("sourceHospitalName")
            .containsEntry("recordedAtHospitalName", "CHU QA")
            .containsEntry("transcribed", true)
            .containsEntry("sourceOfRecord", source);
        PatientTimelineEntryDTO local = visit.stream()
            .filter(r -> r.getEntryId().equals(givenHere.getId().toString())).findFirst().orElseThrow();
        assertThat(local.getMetadata()).containsEntry("sourceHospitalName", "CHU QA")
            .doesNotContainKey("transcribed");
    }

    /** QA D1107: a nursing note's row leads with the title the nurse gave it. */
    @Test
    void d1107_aNursingNoteRowShowsItsTitle() {
        com.example.hms.model.NursingNote note = new com.example.hms.model.NursingNote();
        note.setId(UUID.randomUUID());
        note.setHospital(here);
        note.setTemplate(com.example.hms.model.NursingNoteTemplate.DAR);
        note.setTitle("Réévaluation douleur");
        note.setDataAssessment("Patient calme");
        note.setDocumentedAt(LocalDateTime.now().minusHours(1));
        when(nursingNotes.findTop50ByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, Set.of(here.getId())))
            .thenReturn(List.of(note));

        assertThat(rows.collect(patientId, here.getId(), Set.of("NURSING_NOTE")))
            .extracting(PatientTimelineEntryDTO::getSummary)
            .containsExactly("Note infirmière (DAR) — Réévaluation douleur — Patient calme");
    }
}
