package com.example.hms.service.imaging;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.imaging.dicom.DicomInstanceRef;
import com.example.hms.imaging.dicom.DicomProxyProperties;
import com.example.hms.imaging.dicom.PacsConnection;
import com.example.hms.imaging.dicom.PacsConnectionResolver;
import com.example.hms.imaging.dicom.PacsDicomWebGateway;
import com.example.hms.imaging.dicom.PacsUnavailableException;
import com.example.hms.imaging.dicom.RenderedFrame;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingReport;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.pacs.ImagingStudyViewerDTO;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The in-app viewer: tenancy from the report, the proxy states the portal
 * words, accession matching only on a hospital's own PACS, and the audit row.
 */
class ImagingStudyViewerServiceTest {

    private static final String STUDY = "1.2.840.113619.2.55.3";
    private static final String SERIES = "1.2.840.113619.2.55.3.1";
    private static final String SOP = "1.2.840.113619.2.55.3.1.1";

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private ImagingReportRepository reports;
    private RoleValidator roleValidator;
    private DicomProxyProperties properties;
    private PacsDicomWebGateway gateway;
    private AuditEventLogService audit;
    private ImagingStudyViewerService service;
    private Hospital hospital;
    private ImagingReport report;

    @BeforeEach
    void setUp() {
        reports = mock(ImagingReportRepository.class);
        roleValidator = mock(RoleValidator.class);
        gateway = mock(PacsDicomWebGateway.class);
        audit = mock(AuditEventLogService.class);
        properties = new DicomProxyProperties();
        properties.setEnabled(true);
        service = new ImagingStudyViewerService(reports, roleValidator, new PacsConnectionResolver(properties),
            gateway, audit, properties, Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC),
            TransactionOperations.withoutTransaction());

        hospital = new Hospital();
        hospital.setId(hospitalA);
        hospital.setPacsEnabled(true);
        hospital.setPacsDicomwebBaseUrl("https://pacs.hospital-a.example/dicom-web");
        hospital.setPacsAuthUsername("orthanc");
        hospital.setPacsAuthPassword("s3cret");
        report = new ImagingReport();
        report.setId(UUID.randomUUID());
        report.setHospital(hospital);
        report.setStudyInstanceUid(STUDY);
        when(reports.findById(report.getId())).thenReturn(Optional.of(report));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
    }

    private static DicomInstanceRef instance() {
        return new DicomInstanceRef(SERIES, SOP, 1, 1, 1, "CR");
    }

    @Test
    @DisplayName("a report of another hospital answers as a missing report, and the PACS is never called")
    void otherHospitalIsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);

        assertThatThrownBy(() -> service.manifest(report.getId(), actor))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.frame(report.getId(), SERIES, SOP, 1))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(gateway, audit);
    }

    @Test
    @DisplayName("the hospital's own PACS, with its own credentials, serves the manifest; one audit row")
    void availableThroughTheHospitalsOwnPacs() {
        when(gateway.studyInstances(any(), anyString())).thenReturn(List.of(instance()));

        ImagingStudyViewerDTO dto = service.manifest(report.getId(), actor);

        assertThat(dto.status()).isEqualTo("AVAILABLE");
        assertThat(dto.linkedBy()).isEqualTo("STUDY_UID");
        assertThat(dto.instances()).singleElement()
            .satisfies(i -> assertThat(i.sopInstanceUid()).isEqualTo(SOP));
        ArgumentCaptor<PacsConnection> connection = ArgumentCaptor.forClass(PacsConnection.class);
        verify(gateway).studyInstances(connection.capture(), any());
        assertThat(connection.getValue().source()).isEqualTo(PacsConnection.Source.HOSPITAL);
        assertThat(connection.getValue().username()).isEqualTo("orthanc");
        assertThat(connection.getValue().toString()).doesNotContain("s3cret");
        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(row.capture());
        assertThat(row.getValue().getUserId()).isEqualTo(actor);
        assertThat(row.getValue().getHospitalId()).isEqualTo(hospitalA);
        assertThat(row.getValue().getResourceId()).isEqualTo(report.getId().toString());
    }

    @Test
    @DisplayName("platform flag off: PROXY_DISABLED, nothing reached")
    void proxyDisabled() {
        properties.setEnabled(false);

        assertThat(service.manifest(report.getId(), actor).status()).isEqualTo("PROXY_DISABLED");
        assertThatThrownBy(() -> service.frame(report.getId(), SERIES, SOP, 1))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("hospital switched off and no platform PACS: PACS_NOT_CONFIGURED; with one, the platform PACS answers")
    void notConfiguredThenPlatformFallback() {
        hospital.setPacsEnabled(false);
        assertThat(service.manifest(report.getId(), actor).status()).isEqualTo("PACS_NOT_CONFIGURED");

        properties.setBaseUrl("https://pacs.platform.example/dicom-web");
        when(gateway.studyInstances(any(), anyString())).thenReturn(List.of(instance()));
        assertThat(service.manifest(report.getId(), actor).status()).isEqualTo("AVAILABLE");
        ArgumentCaptor<PacsConnection> connection = ArgumentCaptor.forClass(PacsConnection.class);
        verify(gateway).studyInstances(connection.capture(), any());
        assertThat(connection.getValue().source()).isEqualTo(PacsConnection.Source.PLATFORM);
    }

    @Test
    @DisplayName("no study UID: matched once by accession on the hospital's own PACS, then reused by the frames")
    void accessionMatchOnOwnPacs() {
        report.setStudyInstanceUid(null);
        report.setAccessionNumber("ACC-42");
        when(gateway.studyUidsByAccession(any(), any())).thenReturn(List.of(STUDY));
        when(gateway.studyInstances(any(), any())).thenReturn(List.of(instance()));
        when(gateway.renderedFrame(any(), any(), any(), any(), anyInt()))
            .thenReturn(Optional.of(new RenderedFrame(new byte[] {1}, "image/jpeg")));

        ImagingStudyViewerDTO dto = service.manifest(report.getId(), actor);
        service.frame(report.getId(), SERIES, SOP, 1);
        service.frame(report.getId(), SERIES, SOP, 2);

        assertThat(dto.status()).isEqualTo("AVAILABLE");
        assertThat(dto.linkedBy()).isEqualTo("ACCESSION");
        verify(gateway, times(1)).studyUidsByAccession(any(), any());
        verify(gateway).renderedFrame(any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    @DisplayName("an accession naming two studies is ACCESSION_AMBIGUOUS; on the shared platform PACS it is never matched")
    void accessionAmbiguousOrShared() {
        report.setStudyInstanceUid(null);
        report.setAccessionNumber("ACC-42");
        when(gateway.studyUidsByAccession(any(), any())).thenReturn(List.of(STUDY, "1.2.3.4"));
        assertThat(service.manifest(report.getId(), actor).status()).isEqualTo("ACCESSION_AMBIGUOUS");

        hospital.setPacsEnabled(false);
        properties.setBaseUrl("https://pacs.platform.example/dicom-web");
        assertThat(service.manifest(report.getId(), actor).status()).isEqualTo("NO_STUDY_LINKED");
        verify(gateway, times(1)).studyUidsByAccession(any(), any());
    }

    @Test
    @DisplayName("the PACS down: PACS_UNREACHABLE in the manifest, never a 500")
    void unreachable() {
        when(gateway.studyInstances(any(), any()))
            .thenThrow(new PacsUnavailableException(PacsUnavailableException.Reason.UNREACHABLE));
        assertThat(service.manifest(report.getId(), actor).status()).isEqualTo("PACS_UNREACHABLE");
        verify(audit, never()).logEvent(any());
    }

    @Test
    @DisplayName("an empty study is STUDY_NOT_FOUND")
    void studyNotFound() {
        when(gateway.studyInstances(any(), any())).thenReturn(List.of());
        assertThat(service.manifest(report.getId(), actor).status()).isEqualTo("STUDY_NOT_FOUND");
    }

    @Test
    @DisplayName("a path value that is not a DICOM UID is refused before the report is even read")
    void uidRefusedFirst() {
        assertThatThrownBy(() -> service.frame(report.getId(), "../x", SOP, 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.frame(report.getId(), SERIES, SOP, 0)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reports, gateway);
    }

    @Test
    @DisplayName("a frame the PACS does not have is a 404")
    void missingFrame() {
        when(gateway.renderedFrame(any(), any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.frame(report.getId(), SERIES, SOP, 1))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
