package com.example.hms.i18n;

import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.clinical.DashboardKPI;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO.ComplianceAlertDTO;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ClinicalDashboardServiceImpl;
import com.example.hms.service.impl.SuperAdminOrganizationOverviewServiceImpl;
import com.example.hms.utility.DisplayDates;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ComputedDisplayLocaleTest {
    private MessageSource previous;
    private LocaleContext previousLocale;

    @BeforeEach void messages() {
        previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
        previousLocale = LocaleContextHolder.getLocaleContext();
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach void restore() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.setLocaleContext(previousLocale);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fr|Rendez-vous du jour;Résultats en attente;Patients en consultation;Demandes de renouvellement|planifiés;résultats;patients;demandes",
        "en|Appointments Today;Pending Results;Active Patients;Refill Requests|scheduled;results;patients;requests",
        "es|Citas de hoy;Resultados pendientes;Pacientes en consulta;Solicitudes de renovación|programadas;resultados;pacientes;solicitudes"
    })
    void everyKpiLabelAndUnitUsesTheReaderLocaleWithStableCodesAndValues(String language, String labels, String units) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        var service = mock(ClinicalDashboardServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "staffRepository", mock(StaffRepository.class));
        List<DashboardKPI> rows = ReflectionTestUtils.invokeMethod(service, "generateKPIs", UUID.randomUUID());
        assertThat(rows).extracting(DashboardKPI::getLabel).containsExactly(labels.split(";"));
        assertThat(rows).extracting(DashboardKPI::getUnit).containsExactly(units.split(";"));
        assertThat(rows).extracting(DashboardKPI::getKey).containsExactly("appointments", "pending_results", "patients_today", "tasks");
        assertThat(rows).extracting(DashboardKPI::getValue).containsOnly(0);
        assertThat(rows).extracting(DashboardKPI::getTrend).containsOnly("stable");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "fr|Cette règle est inactive et doit être approuvée.|Les justificatifs de conformité expirent le ",
        "en|Policy is inactive and requires approval|Compliance evidence expires on ",
        "es|La regla está inactiva y requiere aprobación.|Los justificantes de cumplimiento caducan el "
    })
    void calculatedComplianceNotesTranslateWithoutChangingTheirIdentityOrExpiry(String language, String inactive, String expiryPrefix) {
        Locale locale = Locale.forLanguageTag(language);
        LocaleContextHolder.setLocale(locale);
        var service = mock(SuperAdminOrganizationOverviewServiceImpl.class, CALLS_REAL_METHODS);
        var organization = new Organization(); organization.setId(UUID.randomUUID()); organization.setName("cdx");
        var policy = new OrganizationSecurityPolicy(); policy.setId(UUID.randomUUID()); policy.setName("cdx-policy");
        ComplianceAlertDTO disabled = ReflectionTestUtils.invokeMethod(service, "buildInactivePolicyAlert", organization, policy);
        assertThat(disabled.getNotes()).isEqualTo(inactive);
        assertThat(disabled.getOrganizationId()).isEqualTo(organization.getId());
        var rule = new OrganizationSecurityRule(); rule.setId(UUID.randomUUID()); rule.setName("cdx-rule");
        LocalDate date = LocalDate.of(2026, 12, 24); rule.setRuleValue(date.toString());
        ComplianceAlertDTO expiry = ReflectionTestUtils.invokeMethod(service, "buildRuleExpiryAlert", organization, rule);
        assertThat(expiry.getNotes()).startsWith(expiryPrefix.stripTrailing()).contains(DisplayDates.date(date, locale));
        assertThat(expiry.getExpiresOn()).isEqualTo(date);
        assertThat(expiry.getOrganizationId()).isEqualTo(organization.getId());
    }
}
