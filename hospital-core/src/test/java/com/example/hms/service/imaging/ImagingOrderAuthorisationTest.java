package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("QA D4140: an imaging request placed by a nurse or a midwife waits for a physician")
class ImagingOrderAuthorisationTest {

    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final ImagingOrderAuthorisation authorisation = new ImagingOrderAuthorisation(roleValidator);
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID nurse = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();
    private ImagingOrder order;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        order = new ImagingOrder();
        order.setHospital(hospital);
        order.setStatus(ImagingOrderStatus.ORDERED);
        when(roleValidator.isSigningPhysician(doctor, hospitalId)).thenReturn(true);
        when(roleValidator.draftsForPhysicianSignature(nurse, hospitalId)).thenReturn(true);
    }

    /** What the order service does with a new request: stamp who asked, and keep a waiting one pending. */
    private void place(UUID requester, String name) {
        if (authorisation.stampRequest(order, requester, name)) {
            order.setStatus(ImagingOrderStatus.DRAFT);
        }
    }

    @Test
    void aNursesRequestIsKeptPendingWithWhoAsked_whateverStatusItNamed() {
        place(nurse, "Awa Infirmière");

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.DRAFT);
        assertThat(order.getPhysicianAuthorisationRequired()).isTrue();
        assertThat(order.getRequestedByUserId()).isEqualTo(nurse);
        assertThat(order.getRequestedByName()).isEqualTo("Awa Infirmière");
        assertThat(authorisation.awaitsPhysician(order)).isTrue();
    }

    @Test
    void aPhysiciansOrderIsAnOrderAtOnce() {
        place(doctor, "Dr Un");

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        assertThat(order.getPhysicianAuthorisationRequired()).isFalse();
        assertThat(authorisation.awaitsPhysician(order)).isFalse();
        assertThatCode(() -> authorisation.requireAuthorised(order)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = ImagingOrderStatus.class, names = {"DRAFT", "CANCELLED"}, mode = EnumSource.Mode.EXCLUDE)
    void noStepOfTheExamIsTakenOnARequestThatWaits(ImagingOrderStatus target) {
        place(nurse, "Awa Infirmière");

        assertThatThrownBy(() -> authorisation.requireAuthorisedFor(order, target))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessage(ImagingOrderAuthorisation.AWAITING_KEY)
            .satisfies(refused -> assertThat(((FieldBusinessException) refused).getField()).isEqualTo("status"));
    }

    @Test
    void aWaitingRequestCanStillBeKeptPendingOrCalledOff() {
        place(nurse, "Awa Infirmière");

        assertThatCode(() -> authorisation.requireAuthorisedFor(order, ImagingOrderStatus.DRAFT)).doesNotThrowAnyException();
        assertThatCode(() -> authorisation.requireAuthorisedFor(order, ImagingOrderStatus.CANCELLED)).doesNotThrowAnyException();
    }

    @Test
    void onceAPhysicianHasSigned_theOrderGoesAhead_andKeepsWhoAsked() {
        place(nurse, "Awa Infirmière");
        order.setProviderSignedAt(LocalDateTime.now());
        order.setOrderingProviderUserId(doctor);
        order.setStatus(ImagingOrderStatus.ORDERED);

        assertThat(authorisation.awaitsPhysician(order)).isFalse();
        assertThatCode(() -> authorisation.requireExamTookPlace(started(order))).doesNotThrowAnyException();
        assertThat(order.getRequestedByUserId()).isEqualTo(nurse);
        assertThat(order.getPhysicianAuthorisationRequired()).isTrue();
    }

    @Test
    void anOrderWrittenBeforeTheFlag_unsignedAndPlacedByANurse_waitsAllTheSame() {
        order.setOrderingProviderUserId(nurse);

        assertThat(authorisation.awaitsPhysician(order)).isTrue();
        assertThatThrownBy(() -> authorisation.requireAuthorised(order)).isInstanceOf(FieldBusinessException.class);
    }

    @Test
    void anOrderWithNoRecordedProviderKeepsTheRuleItAlwaysHad() {
        assertThat(authorisation.awaitsPhysician(order)).isFalse();
        assertThat(authorisation.needsPhysician(null, hospitalId)).isFalse();
    }

    @Test
    void aPhysicianOfTheHospitalDeclines_andWhoeverAskedWithdraws_nobodyElse() {
        place(nurse, "Awa Infirmière");
        UUID otherNurse = UUID.randomUUID();

        assertThat(authorisation.mayDecline(order, doctor)).isTrue();
        assertThat(authorisation.mayDecline(order, otherNurse)).isFalse();
        assertThat(authorisation.mayWithdraw(order, nurse)).isTrue();
        assertThat(authorisation.mayWithdraw(order, otherNurse)).isFalse();
        assertThat(authorisation.mayWithdraw(order, doctor)).isFalse();

        // A physician's own order is neither declined by a colleague nor "withdrawn" through this door.
        ImagingOrder physicians = new ImagingOrder();
        physicians.setHospital(order.getHospital());
        authorisation.stampRequest(physicians, doctor, "Dr Un");
        assertThat(authorisation.mayDecline(physicians, doctor)).isFalse();
        assertThat(authorisation.mayWithdraw(physicians, doctor)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ImagingOrderStatus.class, names = {"IN_PROGRESS", "COMPLETED", "RESULTS_AVAILABLE"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("QA D4142: a report, its signature and an image wait for the start of the exam")
    void aReportWaitsForTheExam(ImagingOrderStatus status) {
        place(doctor, "Dr Un");
        order.setStatus(status);

        assertThatThrownBy(() -> authorisation.requireExamTookPlace(order))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessage("imaging.report.examNotStarted");
    }

    private static ImagingOrder started(ImagingOrder order) {
        order.setStatus(ImagingOrderStatus.IN_PROGRESS);
        return order;
    }
}
