package com.example.hms.bootstrap;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HospitalOrganizationAlignmentRunnerTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ApplicationArguments args;

    @InjectMocks
    private HospitalOrganizationAlignmentRunner runner;

    @Captor
    private ArgumentCaptor<List<Hospital>> hospitalsCaptor;

    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = Organization.builder()
                .name("Kouritenga Public Health")
                .code("KPL")
                .active(true)
                .build();
        // Simulate an ID being set by JPA
        try {
            var idField = Organization.class.getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(organization, UUID.randomUUID());
        } catch (Exception ignored) {
            // If reflection fails, the organization will just have null id
        }
    }

    // ── run(): organization not found → skip ─────────────────────

    @Test
    void run_organizationNotFound_skipsAlignment() {
        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.empty());

        runner.run(args);

        verify(organizationRepository).findByCode("KPL");
        verifyNoInteractions(hospitalRepository);
    }

    // ── run(): organization found, no unassigned hospitals ───────

    @Test
    void run_organizationFound_noUnassignedHospitals_doesNotSave() {
        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.of(organization));
        when(hospitalRepository.findByOrganizationIsNull()).thenReturn(Collections.emptyList());

        runner.run(args);

        verify(organizationRepository).findByCode("KPL");
        verify(hospitalRepository).findByOrganizationIsNull();
        verify(hospitalRepository, never()).saveAll(anyList());
    }

    // ── run(): organization found, unassigned hospitals → links ──

    @Test
    void run_organizationFound_unassignedHospitals_linksAndSaves() {
        Hospital h1 = Hospital.builder().name("Hospital A").code("H-A").build();
        Hospital h2 = Hospital.builder().name("Hospital B").code("H-B").build();

        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.of(organization));
        when(hospitalRepository.findByOrganizationIsNull()).thenReturn(List.of(h1, h2));
        when(hospitalRepository.saveAll(anyList())).thenReturn(List.of(h1, h2));

        runner.run(args);

        verify(hospitalRepository).saveAll(hospitalsCaptor.capture());
        List<Hospital> saved = hospitalsCaptor.getValue();

        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(h -> assertThat(h.getOrganization()).isEqualTo(organization));
    }

    // ── run(): single unassigned hospital → links ────────────────

    @Test
    void run_singleUnassignedHospital_linksToOrganization() {
        Hospital h1 = Hospital.builder().name("Hospital Solo").code("H-SOLO").build();

        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.of(organization));
        when(hospitalRepository.findByOrganizationIsNull()).thenReturn(List.of(h1));
        when(hospitalRepository.saveAll(anyList())).thenReturn(List.of(h1));

        runner.run(args);

        verify(hospitalRepository).saveAll(hospitalsCaptor.capture());
        List<Hospital> saved = hospitalsCaptor.getValue();

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getOrganization()).isEqualTo(organization);
        assertThat(saved.get(0).getName()).isEqualTo("Hospital Solo");
    }

    // ── run(): organization code constant is "KPL" ───────────────

    @Test
    void run_usesCorrectOrganizationCode() {
        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.empty());

        runner.run(args);

        verify(organizationRepository).findByCode("KPL");
    }

    // ── run(): many unassigned hospitals → all linked ────────────

    @Test
    void run_manyUnassignedHospitals_allLinked() {
        Hospital h1 = Hospital.builder().name("H1").code("C1").build();
        Hospital h2 = Hospital.builder().name("H2").code("C2").build();
        Hospital h3 = Hospital.builder().name("H3").code("C3").build();
        Hospital h4 = Hospital.builder().name("H4").code("C4").build();
        Hospital h5 = Hospital.builder().name("H5").code("C5").build();

        List<Hospital> unassigned = List.of(h1, h2, h3, h4, h5);
        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.of(organization));
        when(hospitalRepository.findByOrganizationIsNull()).thenReturn(unassigned);
        when(hospitalRepository.saveAll(anyList())).thenReturn(unassigned);

        runner.run(args);

        verify(hospitalRepository).saveAll(hospitalsCaptor.capture());
        List<Hospital> saved = hospitalsCaptor.getValue();

        assertThat(saved).hasSize(5);
        saved.forEach(h -> assertThat(h.getOrganization()).isSameAs(organization));
    }

    // ── run(): verify saveAll is called exactly once ─────────────

    @Test
    void run_withUnassignedHospitals_callsSaveAllExactlyOnce() {
        Hospital h1 = Hospital.builder().name("H1").code("C1").build();
        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.of(organization));
        when(hospitalRepository.findByOrganizationIsNull()).thenReturn(List.of(h1));
        when(hospitalRepository.saveAll(anyList())).thenReturn(List.of(h1));

        runner.run(args);

        verify(hospitalRepository, times(1)).saveAll(anyList());
    }

    // ── linkUnassignedHospitals: sets organization on each hospital ──

    @Test
    void run_setsOrganizationOnEveryUnassignedHospital() {
        Hospital h1 = Hospital.builder().name("First").code("F").build();
        Hospital h2 = Hospital.builder().name("Second").code("S").build();
        Hospital h3 = Hospital.builder().name("Third").code("T").build();

        assertThat(h1.getOrganization()).isNull();
        assertThat(h2.getOrganization()).isNull();
        assertThat(h3.getOrganization()).isNull();

        when(organizationRepository.findByCode("KPL")).thenReturn(Optional.of(organization));
        when(hospitalRepository.findByOrganizationIsNull()).thenReturn(List.of(h1, h2, h3));
        when(hospitalRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        runner.run(args);

        assertThat(h1.getOrganization()).isEqualTo(organization);
        assertThat(h2.getOrganization()).isEqualTo(organization);
        assertThat(h3.getOrganization()).isEqualTo(organization);
    }
}
