package com.example.hms.model.discharge;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Embeddable class for tracking test results that are pending at time of discharge
 * Part of Story #14: Discharge Summary Assembly
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class PendingTestResultEntry {

    @Column(name = "test_type", nullable = false, length = 50)
    private String testType; // LAB, IMAGING, PATHOLOGY, CULTURE

    @Column(name = "test_name", nullable = false, length = 255)
    private String testName;

    @Column(name = "test_code", length = 64)
    private String testCode;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "expected_result_date")
    private LocalDate expectedResultDate;

    @Column(name = "ordering_provider", length = 255)
    private String orderingProvider;

    @Column(name = "follow_up_provider", length = 255)
    private String followUpProvider;

    @Column(name = "notification_instructions", length = 1000)
    private String notificationInstructions;

    // Link to actual order for tracking
    @Column(name = "lab_order_id")
    private UUID labOrderId;

    @Column(name = "imaging_order_id")
    private UUID imagingOrderId;

    @Column(name = "is_critical")
    private Boolean isCritical;

    @Column(name = "patient_notified_of_pending")
    private Boolean patientNotifiedOfPending;
}
