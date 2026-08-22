package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * One page of the outbox monitor, plus the queue-level counts the page header
 * needs. The counts cover the caller's whole hospital scope, not just this
 * page — a badge that only counts what happens to be on screen is decoration.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InstrumentOutboxPageDTO {

    private List<InstrumentOutboxResponseDTO> content;
    private int page;
    private int size;
    private long totalElements;

    private long pendingCount;
    private long errorCount;
    private long ackCount;
}
