package com.example.hms.service;

import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.HospitalMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.security.provider.ClinicalHospitals;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA r65 D2500 / D2501: the super-admin chooses a hospital's code, currency
 * and time zone, on create and on edit.
 */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class HospitalCodeCurrencyTimeZoneTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;
    @Mock private ProviderVerificationRepository providerVerificationRepository;

    private HospitalServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HospitalServiceImpl(hospitalRepository, organizationRepository, new HospitalMapper(),
            messageSource, roleValidator, providerVerificationRepository);
        lenient().when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        lenient().when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(hospitalRepository.findClinicalById(any()))
            .thenAnswer(inv -> hospitalRepository.findById(inv.getArgument(0)).filter(ClinicalHospitals::isClinical));
        // An edit loads the row under a lock (review of #1246).
        lenient().when(hospitalRepository.findByIdForUpdate(any()))
            .thenAnswer(inv -> hospitalRepository.findById(inv.getArgument(0)));
    }

    private static HospitalRequestDTO request() {
        return HospitalRequestDTO.builder()
            .name("Centre Médical Test").address("Rue 1").city("Ouagadougou").country("Burkina Faso")
            .phoneNumber("+226 25 30 00 00").active(true).build();
    }

    private Hospital stored(String code) {
        Hospital hospital = Hospital.builder().name("Centre Médical Test").code(code)
            .address("Rue 1").city("Ouagadougou").country("Burkina Faso").phoneNumber("+22625300000").build();
        hospital.setId(UUID.randomUUID());
        when(hospitalRepository.findById(hospital.getId())).thenReturn(Optional.of(hospital));
        return hospital;
    }

    private Hospital saved() {
        ArgumentCaptor<Hospital> captor = ArgumentCaptor.forClass(Hospital.class);
        verify(hospitalRepository).save(captor.capture());
        return captor.getValue();
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: the typed code is kept, in capitals, with the chosen currency and time zone")
    void createKeepsTheTypedCodeCurrencyAndZone() {
        HospitalRequestDTO dto = request();
        dto.setCode(" twp ");
        dto.setCurrencyCode("xaf");
        dto.setTimeZone("Africa/Douala");
        when(hospitalRepository.findByCodeIgnoreCase("TWP")).thenReturn(Optional.empty());

        HospitalResponseDTO response = service.createHospital(dto, Locale.FRENCH);

        Hospital hospital = saved();
        assertThat(hospital.getCode()).isEqualTo("TWP");
        assertThat(hospital.getCurrencyCode()).isEqualTo("XAF");
        assertThat(hospital.getTimeZone()).isEqualTo("Africa/Douala");
        assertThat(response.getCode()).isEqualTo("TWP");
        assertThat(response.getCurrencyCode()).isEqualTo("XAF");
        assertThat(response.getEffectiveTimeZone()).isEqualTo("Africa/Douala");
    }

    @Test
    @DisplayName("create: no code typed generates one; no currency typed is XOF; no zone follows the organisation")
    void createDefaults() {
        HospitalRequestDTO dto = request();
        UUID organisationId = UUID.randomUUID();
        Organization organisation = new Organization();
        organisation.setId(organisationId);
        organisation.setDefaultTimezone("Africa/Ouagadougou");
        dto.setOrganizationId(organisationId);
        when(organizationRepository.findById(organisationId)).thenReturn(Optional.of(organisation));

        HospitalResponseDTO response = service.createHospital(dto, Locale.FRENCH);

        Hospital hospital = saved();
        assertThat(hospital.getCode()).matches("^[A-Z]{3}-[A-Z0-9]{5}$");
        assertThat(hospital.getCurrencyCode()).isEqualTo("XOF");
        assertThat(hospital.getTimeZone()).isNull();
        assertThat(response.getTimeZone()).isNull();
        assertThat(response.getEffectiveTimeZone()).isEqualTo("Africa/Ouagadougou");
        verify(hospitalRepository, never()).findByCodeIgnoreCase(any());
    }

    @Test
    @DisplayName("create: a code another facility holds is a 409 on the code field, whatever its case")
    void createRefusesATakenCode() {
        HospitalRequestDTO dto = request();
        dto.setCode("twp");
        Hospital other = Hospital.builder().name("Autre").code("TWP").build();
        other.setId(UUID.randomUUID());
        when(hospitalRepository.findByCodeIgnoreCase("TWP")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.createHospital(dto, Locale.FRENCH))
            .isInstanceOf(ConflictException.class)
            .hasMessageStartingWith("code:");
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("create: a malformed code, an unsupported currency and a free-text zone are each refused on their field")
    void createRefusesBadValues() {
        HospitalRequestDTO badCode = request();
        badCode.setCode("T W P");
        assertThatThrownBy(() -> service.createHospital(badCode, Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class, r -> assertThat(r.getField()).isEqualTo("code"));

        HospitalRequestDTO badCurrency = request();
        badCurrency.setCurrencyCode("EUR");
        assertThatThrownBy(() -> service.createHospital(badCurrency, Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                r -> assertThat(r.getField()).isEqualTo("currencyCode"));

        HospitalRequestDTO badZone = request();
        badZone.setTimeZone("heure de Ouaga");
        assertThatThrownBy(() -> service.createHospital(badZone, Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class, r -> assertThat(r.getField()).isEqualTo("timeZone"));

        verify(hospitalRepository, never()).save(any());
    }

    // ── update ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update: the code changes while the hospital has issued no invoice number")
    void updateChangesTheCodeBeforeTheFirstInvoice() {
        Hospital hospital = stored("CMT-C5F20");
        HospitalRequestDTO dto = request();
        dto.setCode("TWP");
        when(hospitalRepository.findByCodeIgnoreCase("TWP")).thenReturn(Optional.empty());
        when(hospitalRepository.hasAllocatedInvoiceNumber(hospital.getId())).thenReturn(false);

        service.updateHospital(hospital.getId(), dto, Locale.FRENCH);

        assertThat(hospital.getCode()).isEqualTo("TWP");
    }

    @Test
    @DisplayName("update: once an invoice number exists, a new code is refused on the code field and nothing is saved")
    void updateLocksTheCodeAfterTheFirstInvoice() {
        Hospital hospital = stored("CMT-C5F20");
        HospitalRequestDTO dto = request();
        dto.setCode("TWP");
        when(hospitalRepository.findByCodeIgnoreCase("TWP")).thenReturn(Optional.empty());
        when(hospitalRepository.hasAllocatedInvoiceNumber(hospital.getId())).thenReturn(true);

        UUID id = hospital.getId();

        assertThatThrownBy(() -> service.updateHospital(id, dto, Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class, r -> assertThat(r.getField()).isEqualTo("code"));
        assertThat(hospital.getCode()).isEqualTo("CMT-C5F20");
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("update: the same code (any case) or none is no change, and asks nothing about invoices")
    void updateWithTheSameCodeIsNoChange() {
        Hospital hospital = stored("CMT-C5F20");
        HospitalRequestDTO same = request();
        same.setCode("cmt-c5f20");

        service.updateHospital(hospital.getId(), same, Locale.FRENCH);
        service.updateHospital(hospital.getId(), request(), Locale.FRENCH);

        assertThat(hospital.getCode()).isEqualTo("CMT-C5F20");
        verify(hospitalRepository, never()).hasAllocatedInvoiceNumber(any());
        verify(hospitalRepository, never()).findByCodeIgnoreCase(any());
    }

    @Test
    @DisplayName("update: a code another facility holds is a 409; the hospital's own code is not a clash")
    void updateRefusesAnotherFacilitysCode() {
        Hospital hospital = stored("CMT-C5F20");
        Hospital other = Hospital.builder().name("Autre").code("TWP").build();
        other.setId(UUID.randomUUID());
        HospitalRequestDTO dto = request();
        dto.setCode("TWP");
        when(hospitalRepository.findByCodeIgnoreCase("TWP")).thenReturn(Optional.of(other));

        UUID id = hospital.getId();

        assertThatThrownBy(() -> service.updateHospital(id, dto, Locale.FRENCH))
            .isInstanceOf(ConflictException.class)
            .hasMessageStartingWith("code:");
        assertThat(hospital.getCode()).isEqualTo("CMT-C5F20");
    }

    @Test
    @DisplayName("update: the currency and zone change; an empty zone goes back to the organisation's; an absent one is kept")
    void updateCurrencyAndZone() {
        Hospital hospital = stored("TWP");
        hospital.setTimeZone("Africa/Nairobi");

        HospitalRequestDTO change = request();
        change.setCurrencyCode("XAF");
        change.setTimeZone("Africa/Douala");
        service.updateHospital(hospital.getId(), change, Locale.FRENCH);
        assertThat(hospital.getCurrencyCode()).isEqualTo("XAF");
        assertThat(hospital.getTimeZone()).isEqualTo("Africa/Douala");

        // Neither field sent: both kept.
        service.updateHospital(hospital.getId(), request(), Locale.FRENCH);
        assertThat(hospital.getCurrencyCode()).isEqualTo("XAF");
        assertThat(hospital.getTimeZone()).isEqualTo("Africa/Douala");

        // An empty zone: back to the organisation's default.
        HospitalRequestDTO clear = request();
        clear.setTimeZone("");
        service.updateHospital(hospital.getId(), clear, Locale.FRENCH);
        assertThat(hospital.getTimeZone()).isNull();
    }

    // ── Platform admin rules 2026-11-20, rule 3: one currency per facility, locked once it has billed ──

    @Test
    @DisplayName("update: the currency changes while the hospital has no invoice and no price")
    void updateChangesTheCurrencyBeforeTheFirstInvoiceOrPrice() {
        Hospital hospital = stored("TWP");
        HospitalRequestDTO dto = request();
        dto.setCurrencyCode("XAF");
        when(hospitalRepository.hasPriceListItem(hospital.getId())).thenReturn(false);
        when(hospitalRepository.hasInvoice(hospital.getId())).thenReturn(false);

        service.updateHospital(hospital.getId(), dto, Locale.FRENCH);

        assertThat(hospital.getCurrencyCode()).isEqualTo("XAF");
    }

    @Test
    @DisplayName("update: once a price exists, another currency is refused on its field and nothing is saved")
    void updateLocksTheCurrencyAfterTheFirstPrice() {
        assertTheCurrencyIsLocked(true, false, false);
    }

    @Test
    @DisplayName("update: once an invoice exists (a draft included), another currency is refused the same way")
    void updateLocksTheCurrencyAfterTheFirstInvoice() {
        assertTheCurrencyIsLocked(false, true, false);
    }

    @Test
    @DisplayName("update: an invoice number allocated for an invoice not yet committed locks the currency already")
    void updateLocksTheCurrencyAfterTheFirstInvoiceNumber() {
        assertTheCurrencyIsLocked(false, false, true);
    }

    @Test
    @DisplayName("update: the row is loaded under a lock, before the code and currency rules look at what the hospital has billed")
    void updateLoadsTheRowUnderALockFirst() {
        Hospital hospital = stored("TWP");
        HospitalRequestDTO dto = request();
        dto.setCurrencyCode("XAF");
        dto.setCode("TWQ");

        service.updateHospital(hospital.getId(), dto, Locale.FRENCH);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(hospitalRepository);
        order.verify(hospitalRepository).findByIdForUpdate(hospital.getId());
        order.verify(hospitalRepository).hasAllocatedInvoiceNumber(hospital.getId());
        order.verify(hospitalRepository).hasPriceListItem(hospital.getId());
        verify(hospitalRepository, never()).findClinicalById(any());
    }

    @Test
    @DisplayName("update: a pharmacy or laboratory row is not a hospital here, locked or not")
    void updateRefusesAProviderRow() {
        Hospital pharmacy = stored("PHA");
        pharmacy.setFacilityType(com.example.hms.enums.FacilityType.PHARMACY);
        UUID id = pharmacy.getId();
        HospitalRequestDTO dto = request();

        assertThatThrownBy(() -> service.updateHospital(id, dto, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(hospitalRepository, never()).save(any());
    }

    private void assertTheCurrencyIsLocked(boolean hasPrice, boolean hasInvoice, boolean hasNumber) {
        Hospital hospital = stored("TWP");
        HospitalRequestDTO dto = request();
        dto.setCurrencyCode("XAF");
        lenient().when(hospitalRepository.hasPriceListItem(hospital.getId())).thenReturn(hasPrice);
        lenient().when(hospitalRepository.hasInvoice(hospital.getId())).thenReturn(hasInvoice);
        lenient().when(hospitalRepository.hasAllocatedInvoiceNumber(hospital.getId())).thenReturn(hasNumber);

        UUID id = hospital.getId();

        assertThatThrownBy(() -> service.updateHospital(id, dto, Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                r -> assertThat(r.getField()).isEqualTo("currencyCode"));
        assertThat(hospital.getCurrencyCode()).isEqualTo("XOF");
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("update: the same currency, or none, is no change: a locked hospital still saves its other fields")
    void updateWithTheSameCurrencyIsNoChange() {
        Hospital hospital = stored("TWP");
        HospitalRequestDTO same = request();
        same.setCurrencyCode("xof");

        service.updateHospital(hospital.getId(), same, Locale.FRENCH);
        service.updateHospital(hospital.getId(), request(), Locale.FRENCH);

        assertThat(hospital.getCurrencyCode()).isEqualTo("XOF");
        verify(hospitalRepository, never()).hasPriceListItem(any());
        verify(hospitalRepository, never()).hasInvoice(any());
    }

    @Test
    @DisplayName("only the two CFA francs are offered: any other currency is refused, locked or not")
    void onlyTheTwoCfaFrancs() {
        assertThat(com.example.hms.utility.HospitalLocaleRules.SUPPORTED_CURRENCIES)
            .containsExactlyInAnyOrder("XOF", "XAF");
        Hospital hospital = stored("TWP");
        HospitalRequestDTO dto = request();
        dto.setCurrencyCode("EUR");

        UUID id = hospital.getId();

        assertThatThrownBy(() -> service.updateHospital(id, dto, Locale.FRENCH))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(hospital.getCurrencyCode()).isEqualTo("XOF");
    }
}
