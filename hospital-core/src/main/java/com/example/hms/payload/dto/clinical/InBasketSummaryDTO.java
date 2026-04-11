package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Summary counts of unread In-Basket items by type — used for badge display.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InBasketSummaryDTO {

    private long totalUnread;
    private long resultUnread;
    private long orderUnread;
    private long messageUnread;
    private long taskUnread;
}
