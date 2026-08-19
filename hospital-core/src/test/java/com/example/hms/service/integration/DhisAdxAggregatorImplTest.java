package com.example.hms.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2DataElementMapping;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImmunizationRepository;
import com.example.hms.repository.integration.Dhis2DataElementMappingRepository;
import com.example.hms.service.integration.impl.DhisAdxAggregatorImpl;
import com.example.hms.terminology.TerminologyCodes;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DhisAdxAggregatorImplTest {

    @Mock private ImmunizationRepository immunizationRepository;
    @Mock private Dhis2DataElementMappingRepository mappingRepository;
    @Mock private HospitalRepository hospitalRepository;

    private DhisAdxAggregatorImpl aggregator;
    private UUID hospitalId;
    private Hospital hospital;
    private final LocalDate periodStart = LocalDate.of(2026, 4, 1);
    private final LocalDate periodEnd = LocalDate.of(2026, 4, 30);
    private final String datasetUid = "DS00000DEFK";

    @BeforeEach
    void setUp() {
        aggregator = new DhisAdxAggregatorImpl(
            immunizationRepository, mappingRepository, hospitalRepository);
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setDhis2OrgUnitUid("OU000000001");
    }

    @Test
    @DisplayName("happy path: counts mapped vaccines and emits aggregated values")
    void happyPath() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(immunizationRepository.countByVaccineCodeForHospitalInRange(
            hospitalId, periodStart, periodEnd))
            .thenReturn(List.<Object[]>of(
                new Object[] {"49", 12L},
                new Object[] {"03", 7L}
            ));
        when(mappingRepository
            .findByHospital_IdAndDatasetUidAndHmsConceptSystemAndHmsConceptCodeInAndActiveTrue(
                eq(hospitalId), eq(datasetUid), eq(TerminologyCodes.SYSTEM_CVX), any()))
            .thenReturn(List.of(
                mapping("49", "DE000000049"),
                mapping("03", "DE000000003")));

        var result = aggregator.aggregateImmunizations(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, periodStart, periodEnd);

        assertThat(result.skippedCount()).isZero();
        assertThat(result.values()).hasSize(2);
        assertThat(result.orgUnitUid()).isEqualTo("OU000000001");
        assertThat(result.values()).extracting(AggregatedDataValue::value)
            .containsExactlyInAnyOrder("12", "7");
    }

    @Test
    @DisplayName("vaccine with no mapping increments skipped count and does not emit a value")
    void unmappedVaccineSkipped() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(immunizationRepository.countByVaccineCodeForHospitalInRange(
            hospitalId, periodStart, periodEnd))
            .thenReturn(List.<Object[]>of(
                new Object[] {"49", 12L},
                new Object[] {"99", 1L}
            ));
        when(mappingRepository
            .findByHospital_IdAndDatasetUidAndHmsConceptSystemAndHmsConceptCodeInAndActiveTrue(
                eq(hospitalId), eq(datasetUid), eq(TerminologyCodes.SYSTEM_CVX), any()))
            .thenReturn(List.of(mapping("49", "DE000000049")));

        var result = aggregator.aggregateImmunizations(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, periodStart, periodEnd);

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.values()).hasSize(1);
        assertThat(result.values().get(0).value()).isEqualTo("12");
    }

    @Test
    @DisplayName("PHI-leakage regression: AggregatedDataValue carries no patient identifiers")
    void aggregatedDataValueShape() {
        Field[] fields = AggregatedDataValue.class.getDeclaredFields();
        // The four record components only.
        assertThat(fields).extracting(Field::getName)
            .containsExactlyInAnyOrder("orgUnitUid", "dataElementUid", "categoryOptionComboUid", "value");
    }

    @Test
    @DisplayName("hospital without dhis2_org_unit_uid is rejected")
    void hospitalWithoutOrgUnitUid() {
        Hospital noUid = new Hospital();
        noUid.setId(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(noUid));

        assertThatThrownBy(() -> aggregator.aggregateImmunizations(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, periodStart, periodEnd))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DHIS2 organisation-unit UID");
    }

    @Test
    @DisplayName("missing hospital throws ResourceNotFoundException")
    void hospitalNotFound() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> aggregator.aggregateImmunizations(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, periodStart, periodEnd))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("periodType-mismatch regression: WEEKLY mapping skipped when MONTHLY requested")
    void periodTypeMismatchSkipped() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(immunizationRepository.countByVaccineCodeForHospitalInRange(
            hospitalId, periodStart, periodEnd))
            .thenReturn(List.<Object[]>of(new Object[] {"49", 12L}));
        // The mapping is authored for WEEKLY but the aggregator was called with MONTHLY.
        Dhis2DataElementMapping weeklyMapping = Dhis2DataElementMapping.builder()
            .hospital(hospital)
            .hmsConceptSystem(TerminologyCodes.SYSTEM_CVX)
            .hmsConceptCode("49")
            .dhis2DataElementUid("DE000000049")
            .periodType(Dhis2PeriodType.WEEKLY)
            .datasetUid(datasetUid)
            .active(true)
            .build();
        when(mappingRepository
            .findByHospital_IdAndDatasetUidAndHmsConceptSystemAndHmsConceptCodeInAndActiveTrue(
                eq(hospitalId), eq(datasetUid), eq(TerminologyCodes.SYSTEM_CVX), any()))
            .thenReturn(List.of(weeklyMapping));

        var result = aggregator.aggregateImmunizations(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, periodStart, periodEnd);

        assertThat(result.values()).isEmpty();
        assertThat(result.skippedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("zero immunizations -> empty result, no mapping lookup")
    void emptyAggregation() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(immunizationRepository.countByVaccineCodeForHospitalInRange(
            hospitalId, periodStart, periodEnd))
            .thenReturn(List.of());

        var result = aggregator.aggregateImmunizations(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, periodStart, periodEnd);

        assertThat(result.values()).isEmpty();
        assertThat(result.skippedCount()).isZero();
    }

    private Dhis2DataElementMapping mapping(String cvx, String dataElementUid) {
        return Dhis2DataElementMapping.builder()
            .hospital(hospital)
            .hmsConceptSystem(TerminologyCodes.SYSTEM_CVX)
            .hmsConceptCode(cvx)
            .dhis2DataElementUid(dataElementUid)
            .periodType(Dhis2PeriodType.MONTHLY)
            .datasetUid(datasetUid)
            .active(true)
            .build();
    }
}
