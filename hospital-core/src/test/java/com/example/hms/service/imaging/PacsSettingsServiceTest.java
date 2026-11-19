package com.example.hms.service.imaging;

import com.example.hms.exception.BusinessException;
import com.example.hms.imaging.dicom.DicomProxyProperties;
import com.example.hms.imaging.dicom.PacsConnectionResolver;
import com.example.hms.imaging.dicom.PacsDicomWebGateway;
import com.example.hms.imaging.dicom.PacsUnavailableException;
import com.example.hms.imaging.dicom.PacsUrlPolicy;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsSettingsDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsSettingsUpdateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hospital admin's PACS settings: the password is write-only everywhere
 * (response, JSON, toString, audit), the URL is held to the SSRF fence, the
 * viewer link to http(s).
 */
class PacsSettingsServiceTest {

    private static final String SECRET = "Pacs-S3cret!";
    private static final String PUBLIC_URL = "https://93.184.216.34/dicom-web";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HospitalRepository hospitals;
    private AuditEventLogService audit;
    private PacsDicomWebGateway gateway;
    private DicomProxyProperties properties;
    private PacsSettingsService service;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospitals = mock(HospitalRepository.class);
        audit = mock(AuditEventLogService.class);
        gateway = mock(PacsDicomWebGateway.class);
        properties = new DicomProxyProperties();
        properties.setEnabled(true);
        service = new PacsSettingsService(hospitals, new PacsConnectionResolver(properties), gateway, audit);
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        when(hospitals.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(hospitals.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PacsSettingsUpdateRequestDTO body(boolean enabled, String url, String template, String user,
                                                     String password, Boolean clear) {
        return new PacsSettingsUpdateRequestDTO(enabled, url, template, user, password, clear);
    }

    @Test
    @DisplayName("the password is stored but never returned, serialised, printed or audited")
    void passwordIsWriteOnly() {
        PacsSettingsDTO dto = service.set(hospital.getId(),
            body(true, PUBLIC_URL + "/", "https://viewer.example/?s={studyInstanceUid}", "orthanc", SECRET, null), null);

        assertThat(hospital.getPacsAuthPassword()).isEqualTo(SECRET);
        assertThat(hospital.getPacsDicomwebBaseUrl()).isEqualTo(PUBLIC_URL);
        assertThat(dto.passwordSet()).isTrue();
        assertThat(dto.state()).isEqualTo("READY");
        assertThat(dto.effectiveSource()).isEqualTo("HOSPITAL");
        assertThat(JSON.writeValueAsString(dto)).doesNotContain(SECRET);
        assertThat(JSON.writeValueAsString(hospital)).doesNotContain(SECRET).doesNotContain("pacsAuthPassword");
        PacsSettingsUpdateRequestDTO request = body(true, PUBLIC_URL, null, "u", SECRET, null);
        assertThat(request.toString()).doesNotContain(SECRET);
        assertThat(JSON.writeValueAsString(request)).doesNotContain(SECRET);
        assertThat(hospital.toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("the audit row names the changed fields, never the password value")
    void auditNamesFieldsOnly() {
        service.set(hospital.getId(), body(true, PUBLIC_URL, null, "orthanc", SECRET, null), UUID.randomUUID());
        // No transaction in a unit test: afterCommit runs the action at once.
        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(row.capture());
        assertThat(row.getValue().getEventDescription()).isEqualTo("PACS settings changed");
        assertThat(String.valueOf(row.getValue().getDetails())).contains("authPassword");
        assertThat(String.valueOf(row.getValue().getDetails())).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a blank password keeps the stored one; clearPassword removes it")
    void blankKeepsClearRemoves() {
        hospital.setPacsAuthPassword(SECRET);
        service.set(hospital.getId(), body(true, PUBLIC_URL, null, "orthanc", "  ", null), null);
        assertThat(hospital.getPacsAuthPassword()).isEqualTo(SECRET);

        PacsSettingsDTO dto = service.set(hospital.getId(), body(true, PUBLIC_URL, null, "orthanc", null, true), null);
        assertThat(hospital.getPacsAuthPassword()).isNull();
        assertThat(dto.passwordSet()).isFalse();
    }

    @Test
    @DisplayName("switching on needs a URL; an internal URL is refused (SSRF); a script viewer link is refused")
    void validation() {
        assertThatThrownBy(() -> service.set(hospital.getId(), body(true, " ", null, null, null, null), null))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(PacsSettingsService.URL_REQUIRED));
        assertThatThrownBy(() -> service.set(hospital.getId(),
            body(true, "https://169.254.169.254/latest", null, null, null, null), null))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(PacsUrlPolicy.PRIVATE_HOST));
        assertThatThrownBy(() -> service.set(hospital.getId(),
            body(false, null, "javascript:alert('{studyInstanceUid}')", null, null, null), null))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(PacsSettingsService.VIEWER_TEMPLATE_INVALID));
        verify(hospitals, never()).save(any());
    }

    @Test
    @DisplayName("the connection test reports a state, never throws: disabled, OK, unauthorised")
    void connectionTest() {
        properties.setEnabled(false);
        assertThat(service.test(hospital.getId()).result()).isEqualTo("PROXY_DISABLED");

        properties.setEnabled(true);
        assertThat(service.test(hospital.getId()).result()).isEqualTo("NOT_CONFIGURED");

        hospital.setPacsEnabled(true);
        hospital.setPacsDicomwebBaseUrl(PUBLIC_URL);
        assertThat(service.test(hospital.getId()).result()).isEqualTo("OK");

        doThrow(new PacsUnavailableException(PacsUnavailableException.Reason.UNAUTHORIZED)).when(gateway).ping(any());
        assertThat(service.test(hospital.getId())).satisfies(r -> {
            assertThat(r.result()).isEqualTo("UNAUTHORIZED");
            assertThat(r.source()).isEqualTo("HOSPITAL");
        });
    }
}
