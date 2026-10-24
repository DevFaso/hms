package com.example.hms.service.maternity;

import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborAlertType;
import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.enums.PostpartumAlertType;
import com.example.hms.model.Patient;
import com.example.hms.model.labor.LaborAlert;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.neonatal.NewbornAssessmentAlert;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.model.postpartum.PostpartumObservationAlert;
import com.example.hms.repository.LaborPartographEntryRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.PostpartumObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** QA D605: when an urgent obstetric alert stops warranting reminders. */
@ExtendWith(MockitoExtension.class)
class ObstetricAlertCurrencyTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 23, 10, 0);

    @Mock private LaborPartographEntryRepository partographRepository;
    @Mock private NewbornAssessmentRepository newbornRepository;
    @Mock private PostpartumObservationRepository postpartumRepository;

    private ObstetricAlertCurrency currency;

    @BeforeEach
    void setUp() {
        currency = new ObstetricAlertCurrency(partographRepository, newbornRepository, postpartumRepository);
    }

    private static ObstetricAlertEscalation row(String sourceType, UUID sourceId, String alertType) {
        return ObstetricAlertEscalation.builder().sourceType(sourceType).sourceId(sourceId).alertType(alertType).build();
    }

    private static LaborPartographEntry entry(LaborEpisode episode, LocalDateTime at, LaborAlertSeverity severity) {
        LaborPartographEntry e = new LaborPartographEntry();
        e.setId(UUID.randomUUID());
        e.setEpisode(episode);
        e.setObservationTime(at);
        e.setFetalHeartRateBpm(severity == LaborAlertSeverity.URGENT ? 95 : 140);
        if (severity != null) {
            e.setAlerts(List.of(LaborAlert.builder().type(LaborAlertType.FETAL_HEART_RATE).severity(severity)
                .message("m").build()));
        }
        return e;
    }

    private static LaborEpisode episode(LaborStatus status) {
        LaborEpisode ep = new LaborEpisode();
        ep.setId(UUID.randomUUID());
        ep.setStatus(status);
        return ep;
    }

    private ObstetricAlertEscalation partographRow(LaborPartographEntry source) {
        when(partographRepository.findById(source.getId())).thenReturn(Optional.of(source));
        return row(ObstetricAlertEscalationService.SOURCE_PARTOGRAPH, source.getId(), "FETAL_HEART_RATE");
    }

    @Test
    void laterNormalPartographEntryResolves() {
        LaborEpisode ep = episode(LaborStatus.ACTIVE);
        ObstetricAlertEscalation r = partographRow(entry(ep, T0, LaborAlertSeverity.URGENT));
        when(partographRepository.findByEpisode_IdAndObservationTimeAfterOrderByObservationTimeDesc(ep.getId(), T0))
            .thenReturn(List.of(entry(ep, T0.plusMinutes(15), null)));
        assertThat(currency.noLongerUrgent(r)).isTrue();
    }

    @Test
    void laterStillUrgentPartographEntryKeepsTheAlert() {
        LaborEpisode ep = episode(LaborStatus.ACTIVE);
        ObstetricAlertEscalation r = partographRow(entry(ep, T0, LaborAlertSeverity.URGENT));
        when(partographRepository.findByEpisode_IdAndObservationTimeAfterOrderByObservationTimeDesc(ep.getId(), T0))
            .thenReturn(List.of(entry(ep, T0.plusMinutes(15), LaborAlertSeverity.URGENT)));
        assertThat(currency.noLongerUrgent(r)).isFalse();
    }

    @Test
    void noLaterEntryKeepsTheAlert() {
        LaborEpisode ep = episode(LaborStatus.ACTIVE);
        ObstetricAlertEscalation r = partographRow(entry(ep, T0, LaborAlertSeverity.URGENT));
        when(partographRepository.findByEpisode_IdAndObservationTimeAfterOrderByObservationTimeDesc(ep.getId(), T0))
            .thenReturn(List.of());
        assertThat(currency.noLongerUrgent(r)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D658: a later partograph entry that did not chart the FHR never closes an FHR alert")
    void laterPartographEntryWithoutTheValueKeepsTheAlert() {
        LaborEpisode ep = episode(LaborStatus.ACTIVE);
        ObstetricAlertEscalation r = partographRow(entry(ep, T0, LaborAlertSeverity.URGENT));
        LaborPartographEntry withoutFhr = entry(ep, T0.plusMinutes(15), null);
        withoutFhr.setFetalHeartRateBpm(null);
        when(partographRepository.findByEpisode_IdAndObservationTimeAfterOrderByObservationTimeDesc(ep.getId(), T0))
            .thenReturn(List.of(withoutFhr));
        assertThat(currency.noLongerUrgent(r)).isFalse();
        // The newest entry that re-measured it decides, even behind a blank one.
        when(partographRepository.findByEpisode_IdAndObservationTimeAfterOrderByObservationTimeDesc(ep.getId(), T0))
            .thenReturn(List.of(withoutFhr, entry(ep, T0.plusMinutes(10), null)));
        assertThat(currency.noLongerUrgent(r)).isTrue();
    }

    @Test
    void closedEpisodeResolves() {
        LaborPartographEntry source = entry(episode(LaborStatus.DELIVERED), T0, LaborAlertSeverity.URGENT);
        assertThat(currency.noLongerUrgent(partographRow(source))).isTrue();
    }

    @Test
    void unreadableOrUnsupersedableSourcesKeepTheAlert() {
        UUID id = UUID.randomUUID();
        when(partographRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(currency.noLongerUrgent(row(ObstetricAlertEscalationService.SOURCE_PARTOGRAPH, id, "X"))).isFalse();
        assertThat(currency.noLongerUrgent(row(ObstetricAlertEscalationService.SOURCE_DELIVERY, id, "X"))).isFalse();
        assertThat(currency.noLongerUrgent(row(null, id, "X"))).isFalse();
        assertThat(currency.noLongerUrgent(row(ObstetricAlertEscalationService.SOURCE_NEWBORN, null, "X"))).isFalse();
        when(newbornRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(currency.noLongerUrgent(row(ObstetricAlertEscalationService.SOURCE_NEWBORN, id, "X"))).isFalse();
        when(postpartumRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(currency.noLongerUrgent(row(ObstetricAlertEscalationService.SOURCE_POSTPARTUM, id, "X"))).isFalse();
    }

    private static NewbornAssessment assessment(Patient baby, LocalDateTime at, NewbornAlertSeverity severity) {
        NewbornAssessment a = new NewbornAssessment();
        a.setId(UUID.randomUUID());
        a.setPatient(baby);
        a.setAssessmentTime(at);
        a.setRespirationsPerMin(severity == NewbornAlertSeverity.URGENT ? 90 : 45);
        if (severity != null) {
            a.setAlerts(List.of(NewbornAssessmentAlert.builder().type(NewbornAlertType.RESPIRATORY)
                .severity(severity).message("m").build()));
        }
        return a;
    }

    @Test
    void newbornLaterAssessmentDecides() {
        Patient baby = new Patient();
        baby.setId(UUID.randomUUID());
        NewbornAssessment source = assessment(baby, T0, NewbornAlertSeverity.URGENT);
        when(newbornRepository.findById(source.getId())).thenReturn(Optional.of(source));
        ObstetricAlertEscalation r = row(ObstetricAlertEscalationService.SOURCE_NEWBORN, source.getId(), "RESPIRATORY");
        when(newbornRepository.findByPatient_IdAndAssessmentTimeAfterOrderByAssessmentTimeDesc(baby.getId(), T0))
            .thenReturn(List.of(assessment(baby, T0.plusHours(1), null)));
        assertThat(currency.noLongerUrgent(r)).isTrue();
        when(newbornRepository.findByPatient_IdAndAssessmentTimeAfterOrderByAssessmentTimeDesc(baby.getId(), T0))
            .thenReturn(List.of(assessment(baby, T0.plusHours(1), NewbornAlertSeverity.URGENT)));
        assertThat(currency.noLongerUrgent(r)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D658: a later newborn assessment that measured SpO2 only never closes the glucose alert")
    void newbornLaterAssessmentWithoutTheValueKeepsTheAlert() {
        Patient baby = new Patient();
        baby.setId(UUID.randomUUID());
        NewbornAssessment source = assessment(baby, T0, null);
        source.setGlucoseMgDl(30);
        source.setAlerts(List.of(NewbornAssessmentAlert.builder().type(NewbornAlertType.GLUCOSE)
            .severity(NewbornAlertSeverity.URGENT).message("m").build()));
        when(newbornRepository.findById(source.getId())).thenReturn(Optional.of(source));
        ObstetricAlertEscalation r = row(ObstetricAlertEscalationService.SOURCE_NEWBORN, source.getId(), "GLUCOSE");
        NewbornAssessment spo2Only = assessment(baby, T0.plusMinutes(5), null);
        spo2Only.setRespirationsPerMin(null);
        spo2Only.setOxygenSaturationPercent(84);
        when(newbornRepository.findByPatient_IdAndAssessmentTimeAfterOrderByAssessmentTimeDesc(baby.getId(), T0))
            .thenReturn(List.of(spo2Only));
        assertThat(currency.noLongerUrgent(r)).isFalse();
        NewbornAssessment normalGlucose = assessment(baby, T0.plusMinutes(30), null);
        normalGlucose.setGlucoseMgDl(60);
        when(newbornRepository.findByPatient_IdAndAssessmentTimeAfterOrderByAssessmentTimeDesc(baby.getId(), T0))
            .thenReturn(List.of(normalGlucose, spo2Only));
        assertThat(currency.noLongerUrgent(r)).isTrue();
    }

    private static PostpartumObservation observation(PostpartumCarePlan plan, LocalDateTime at,
                                                     PostpartumAlertSeverity severity) {
        PostpartumObservation o = new PostpartumObservation();
        o.setId(UUID.randomUUID());
        o.setCarePlan(plan);
        o.setObservationTime(at);
        o.setSystolicBpMmHg(severity == PostpartumAlertSeverity.URGENT ? 80 : 115);
        o.setPulseBpm(severity == PostpartumAlertSeverity.URGENT ? 125 : 80);
        if (severity != null) {
            o.addAlert(PostpartumObservationAlert.builder().type(PostpartumAlertType.HEMORRHAGE)
                .severity(severity).message("m").build());
        }
        return o;
    }

    @Test
    void postpartumPlanAndLaterObservationDecide() {
        PostpartumCarePlan plan = new PostpartumCarePlan();
        plan.setId(UUID.randomUUID());
        plan.setActive(true);
        PostpartumObservation source = observation(plan, T0, PostpartumAlertSeverity.URGENT);
        when(postpartumRepository.findById(source.getId())).thenReturn(Optional.of(source));
        ObstetricAlertEscalation r = row(ObstetricAlertEscalationService.SOURCE_POSTPARTUM, source.getId(), "HEMORRHAGE");
        when(postpartumRepository.findByCarePlan_IdAndObservationTimeAfterOrderByObservationTimeDesc(plan.getId(), T0))
            .thenReturn(List.of(observation(plan, T0.plusMinutes(30), PostpartumAlertSeverity.URGENT)));
        assertThat(currency.noLongerUrgent(r)).isFalse();
        when(postpartumRepository.findByCarePlan_IdAndObservationTimeAfterOrderByObservationTimeDesc(plan.getId(), T0))
            .thenReturn(List.of(observation(plan, T0.plusMinutes(30), null)));
        assertThat(currency.noLongerUrgent(r)).isTrue();
        plan.setActive(false);
        assertThat(currency.noLongerUrgent(r)).isTrue();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D658: a later postpartum observation without the vitals, or a checkbox trigger, keeps the alert")
    void postpartumLaterObservationWithoutTheValuesKeepsTheAlert() {
        PostpartumCarePlan plan = new PostpartumCarePlan();
        plan.setId(UUID.randomUUID());
        plan.setActive(true);
        PostpartumObservation source = observation(plan, T0, PostpartumAlertSeverity.URGENT);
        when(postpartumRepository.findById(source.getId())).thenReturn(Optional.of(source));
        ObstetricAlertEscalation r = row(ObstetricAlertEscalationService.SOURCE_POSTPARTUM, source.getId(), "HEMORRHAGE");
        PostpartumObservation painOnly = observation(plan, T0.plusMinutes(30), null);
        painOnly.setSystolicBpMmHg(null);
        painOnly.setPulseBpm(null);
        when(postpartumRepository.findByCarePlan_IdAndObservationTimeAfterOrderByObservationTimeDesc(plan.getId(), T0))
            .thenReturn(List.of(painOnly));
        assertThat(currency.noLongerUrgent(r)).isFalse();
        source.setUterineAtonySuspected(true);
        assertThat(currency.noLongerUrgent(r)).as("a checkbox cannot be re-measured").isFalse();
    }
}
