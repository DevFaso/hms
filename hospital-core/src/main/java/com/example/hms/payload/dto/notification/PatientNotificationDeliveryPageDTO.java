package com.example.hms.payload.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** One page of a hospital's patient notification sends, with the hospital's counts per status. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientNotificationDeliveryPageDTO {
    private List<PatientNotificationDeliveryDTO> content;
    private int page;
    private int size;
    private long totalElements;
    /** Every status, zero included: PENDING, RETRYING, SENT, FAILED, SKIPPED. */
    private Map<String, Long> countsByStatus;
}
