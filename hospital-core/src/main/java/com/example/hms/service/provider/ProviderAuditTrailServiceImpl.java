package com.example.hms.service.provider;

import com.example.hms.payload.dto.provider.ProviderAuditEntryDTO;
import com.example.hms.payload.dto.provider.ProviderAuditPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The provider facility audit trail (plan §3.1). The rows are those of
 * {@link AuditEventLogRepository#findProviderFacilityTrail}: written by the
 * facility's staff while acting there, never a row about a patient, and
 * projected to ids and codes in the query itself.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProviderAuditTrailServiceImpl implements ProviderAuditTrailService {

    private final ProviderSeatResolver seatResolver;
    private final AuditEventLogRepository auditEventLogRepository;

    @Override
    public Optional<ProviderAuditPageDTO> trail(String page, String size) {
        // Who first: the seat is checked before either parameter is read.
        Optional<ProviderSeat> found = seatResolver.currentAdmin();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int pageNumber = parse(page, 0, "page");
        int pageSize = Math.min(parse(size, DEFAULT_PAGE_SIZE, "size"), MAX_PAGE_SIZE);
        if (pageSize < 1) {
            throw new IllegalArgumentException("The page size must be at least 1.");
        }
        Page<ProviderAuditEntryDTO> rows = auditEventLogRepository.findProviderFacilityTrail(
            found.get().facility().getId(), PageRequest.of(pageNumber, pageSize));
        return Optional.of(ProviderAuditPageDTO.builder()
            .entries(rows.getContent())
            .page(pageNumber)
            .size(pageSize)
            .totalElements(rows.getTotalElements())
            .hasMore(rows.hasNext())
            .build());
    }

    /** A blank value is the default; anything else must be a non-negative whole number. */
    private static int parse(String raw, int fallback, String name) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException("The " + name + " must not be negative.");
            }
            return value;
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("The " + name + " must be a whole number.");
        }
    }
}
