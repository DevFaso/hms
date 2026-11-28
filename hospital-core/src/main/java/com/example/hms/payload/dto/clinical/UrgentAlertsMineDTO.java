package com.example.hms.payload.dto.clinical;

import java.time.LocalDateTime;
import java.util.List;

/**
 * QA D4100, D4101 (r81): the urgent alerts of the escalation ladder that wait on the reader at one hospital.
 *
 * @param toAcknowledge       the alerts the reader holds an item for and may acknowledge
 * @param documentedWaiting   the alerts the reader documented that nobody else has acknowledged yet
 * @param oldestRaisedAt      when the oldest of all of them was raised; null when there is none
 * @param items               the reader's items to acknowledge, the oldest alert first
 */
public record UrgentAlertsMineDTO(long toAcknowledge, long documentedWaiting, LocalDateTime oldestRaisedAt,
                                  List<InBasketItemDTO> items) {
}
