package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabResultComponent;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Notification;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.i18n.TestMessageSources;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

/**
 * r65 D2623: a critical NFS could not be acknowledged. Every read-back but the
 * full 13-value stored line was refused, so the panel paged for six hours. The
 * read-back of a panel confirms its critical component(s): name and value,
 * unit optional, decimal comma or point. Single tests are unchanged.
 * r65 D2183: the admins paged from round 2 are asked to get a clinician to
 * read the value back, not to acknowledge it themselves.
 */
@ExtendWith(MockitoExtension.class)
class CriticalPanelReadBackTest {

    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private RenderingNotificationService notificationService;
    @Mock private SmsService smsService;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabResultMapper labResultMapper;
    @Mock private com.example.hms.repository.StaffRepository staffRepository;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Spy private MessageSource messageSource = TestMessageSources.bundles();
    @InjectMocks private CriticalValueNotificationService service;

    private LabResult panel;
    private LabTestDefinition nfs;

    private static LabTestReferenceRange range(double min, double max, Double critLow, Double critHigh) {
        return LabTestReferenceRange.builder().minValue(min).maxValue(max)
            .criticalLow(critLow).criticalHigh(critHigh).build();
    }

    private static LabResultComponent value(String code, String name, String value, String unit) {
        return LabResultComponent.builder().code(code).name(name).value(value).unit(unit).build();
    }

    @BeforeEach
    void setUp() {
        LockAssert.TestHelper.makeAllAssertsPass(true);
        User orderer = new User();
        orderer.setId(UUID.randomUUID());
        orderer.setUsername("dr.diallo");
        Staff staff = Staff.builder().build();
        staff.setUser(orderer);
        Patient patient = new Patient();
        patient.setFirstName("Awa");
        patient.setLastName("TestDiallo");

        nfs = new LabTestDefinition();
        nfs.setName("NFS");
        nfs.setTestCode("NFS");
        nfs.setComponents(new ArrayList<>(List.of(
            LabTestComponent.builder().code("HB").name("Hémoglobine").unit("g/dL")
                .referenceRanges(new ArrayList<>(List.of(range(12, 16, 7.0, 20.0)))).build(),
            LabTestComponent.builder().code("HCT").name("Hématocrite").unit("%")
                .referenceRanges(new ArrayList<>(List.of(range(36, 48, 15.0, 60.0)))).build(),
            LabTestComponent.builder().code("PLT").name("Plaquettes").unit("10^9/L")
                .referenceRanges(new ArrayList<>(List.of(range(150, 400, 20.0, 1000.0)))).build())));

        LabOrder order = new LabOrder();
        order.setOrderingStaff(staff);
        order.setPatient(patient);
        order.setLabTestDefinition(nfs);

        panel = new LabResult();
        panel.setId(UUID.randomUUID());
        panel.setLabOrder(order);
        panel.setReleased(true);
        panel.setAbnormalFlag(AbnormalFlag.CRITICAL);
        panel.setCriticalNotifiedAt(LocalDateTime.now().minusHours(1));
        panel.setComponents(new ArrayList<>(List.of(
            value("HB", "Hémoglobine", "5.5", "g/dL"),
            value("HCT", "Hématocrite", "42", "%"),
            value("PLT", "Plaquettes", "180", "10^9/L"))));
        panel.setResultValue("HB 5.5 g/dL; HCT 42 %; PLT 180 10^9/L");

        lenient().when(notificationService.created(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(labResultRepository.confirmReadBackIfUnconfirmed(any(), any(), any(), any(), any()))
            .thenReturn(1);
        lenient().when(labResultRepository.recordReadBackMismatchIfUnconfirmed(any(), any(), any(), any(), any()))
            .thenReturn(1);
    }

    @ParameterizedTest(name = "\"{0}\" confirms HB 5.5")
    @ValueSource(strings = {
        "HB 5,5 g/dL", "HB 5,5", "hb 5.5", "HB: 5,5 g/dl", "Hémoglobine 5,5 g/dL", "hemoglobine 5.50",
        "5,5", "5,5 g/dL", "5.5",
        // the full stored line, as before
        "HB 5.5 g/dL; HCT 42 %; PLT 180 10^9/L",
        // naming a non-critical component as well is fine when it is right
        "HB 5,5 g/dL et HCT 42 %", "HB 5,5, PLT 180",
    })
    void theCriticalComponentReadBackAcknowledgesThePanelAndStopsEscalation(String repeated) {
        LabResult updated = service.recordReadBack(panel, repeated, UUID.randomUUID(), "Dr Diallo");

        assertThat(updated.isAcknowledged()).isTrue();
        assertThat(updated.getCriticalReadBackAt()).isNotNull();
        assertThat(updated.getCriticalReadBackValue()).isEqualTo(repeated);
        verify(notificationService).markReadForSubject(panel.getId(),
            List.of("CRITICAL_LAB_RESULT", "CRITICAL_LAB_RESULT_ESCALATION"));
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
        "HB 5,6", "5,6", "HB 55", "HB 5,5 mmol/L", "HCT 42 %", "PLT 180", "acquitter", "HB",
        "HB 5,5; HCT 41", "HBX 5,5", "HB 5,5; CRP 3",
    })
    void aWrongOrIncompleteReadBackIsRefusedAndNothingIsAcknowledged(String repeated) {
        assertThatThrownBy(() -> service.recordReadBack(panel, repeated, UUID.randomUUID(), "Dr Diallo"))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("lab.criticalReadBack.mismatch");
        assertThat(panel.isAcknowledged()).isFalse();
        verify(labResultRepository, never()).confirmReadBackIfUnconfirmed(any(), any(), any(), any(), any());
    }

    @Test
    void withTwoCriticalComponentsBothMustBeNamed() {
        panel.getComponents().set(2, value("PLT", "Plaquettes", "15", "10^9/L"));

        assertThatThrownBy(() -> service.recordReadBack(panel, "5,5", UUID.randomUUID(), "Dr A"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.recordReadBack(panel, "HB 5,5 g/dL", UUID.randomUUID(), "Dr A"))
            .isInstanceOf(BusinessException.class);

        LabResult updated = service.recordReadBack(panel, "HB 5,5 g/dL ; Plaquettes 15", UUID.randomUUID(), "Dr A");
        assertThat(updated.isAcknowledged()).isTrue();
    }

    @Test
    void aSingleTestReadBackIsUnchanged() {
        panel.setComponents(new ArrayList<>());
        panel.setResultValue("7.1");
        panel.setResultUnit("mmol/L");

        assertThatThrownBy(() -> service.recordReadBack(panel, "HB 7,1", UUID.randomUUID(), "Dr A"))
            .isInstanceOf(BusinessException.class);
        assertThat(service.recordReadBack(panel, "7,1 mmol/L", UUID.randomUUID(), "Dr A").isAcknowledged()).isTrue();
    }

    @Test
    void thePanelsCriticalComponentIsGradedFromItsOwnRange() {
        assertThat(LabResultMapper.gradedComponentsOf(panel))
            .extracting(c -> c.getCode() + "=" + c.getSeverityFlag())
            .containsExactly("HB=CRITICAL", "HCT=NORMAL", "PLT=NORMAL");
    }

    // ── D2183 ────────────────────────────────────────────────────────────

}
