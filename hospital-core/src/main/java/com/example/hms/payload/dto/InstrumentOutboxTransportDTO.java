package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Read-only view of the outbound MLLP transport configuration.
 *
 * <p>A queue full of PENDING rows looks identical whether the analyser is down
 * or the transport is simply switched off — and the only signal for the latter
 * was a server-side DEBUG log line. No credentials cross the wire here; host
 * and port are operational facts the lab staff already know from the analyser
 * itself.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InstrumentOutboxTransportDTO {

    private boolean enabled;
    private String host;
    private int port;
    private int maxAttempts;
    private int retryAfterSeconds;
    private int batchSize;
}
