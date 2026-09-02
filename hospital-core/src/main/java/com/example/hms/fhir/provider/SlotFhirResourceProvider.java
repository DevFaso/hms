package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.SlotFhirMapper;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Slot;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 {@code Slot} provider (Tier 2 item 43): the bookable-time
 * inventory V121/V128 built, exposed. Read-only — holds and bookings go
 * through the slot writer with its optimistic-lock ceremony.
 *
 * <p>The search window defaults to the next 14 days: an unbounded slot
 * search is an unbounded table scan, and the near future is the question a
 * scheduling client is actually asking.
 */
@Component
// Read-only TX — see AppointmentFhirResourceProvider: open-in-view=false
// makes post-transaction lazy walks throw; readOnly keeps replica routing.
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class SlotFhirResourceProvider implements IResourceProvider {

    private static final int MAX_SLOTS = 500;
    private static final int DEFAULT_WINDOW_DAYS = 14;

    private final AppointmentSlotRepository slotRepository;
    private final SlotFhirMapper mapper;
    private final Clock clock;

    public SlotFhirResourceProvider(AppointmentSlotRepository slotRepository,
                                    SlotFhirMapper mapper,
                                    Clock clock) {
        this.slotRepository = slotRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Class<Slot> getResourceType() {
        return Slot.class;
    }

    @Read
    public Slot read(@IdParam IdType id) {
        UUID hospitalId = FhirTenancy.requireHospitalScope("Slot");
        UUID uuid = FhirIds.parseOrThrow(id);
        return slotRepository.findById(uuid)
            .filter(s -> s.getHospital() != null && hospitalId.equals(s.getHospital().getId()))
            .map(mapper::toFhir)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<Slot> search(@OptionalParam(name = "start") DateRangeParam start) {
        UUID hospitalId = FhirTenancy.requireHospitalScope("Slot");
        LocalDate today = LocalDate.now(clock);
        LocalDate lower = start != null && start.getLowerBoundAsInstant() != null
            ? start.getLowerBoundAsInstant().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            : null;
        LocalDate upper = start != null && start.getUpperBoundAsInstant() != null
            ? start.getUpperBoundAsInstant().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            : null;
        // A one-sided range derives its missing edge from the bound that WAS
        // given, not from today: start=ge<nextMonth> with a today-anchored
        // upper edge is an inverted window that silently returns nothing.
        LocalDate from;
        LocalDate to;
        if (lower == null && upper == null) {
            from = today;
            to = today.plusDays(DEFAULT_WINDOW_DAYS);
        } else if (upper == null) {
            from = lower;
            to = lower.plusDays(DEFAULT_WINDOW_DAYS);
        } else if (lower == null) {
            from = upper.minusDays(DEFAULT_WINDOW_DAYS);
            to = upper;
        } else {
            from = lower;
            to = upper;
        }
        return slotRepository.findByHospital_IdAndSlotDateBetweenOrderByStartAtAsc(
                hospitalId, from, to, PageRequest.of(0, MAX_SLOTS))
            .map(mapper::toFhir)
            .filter(java.util.Objects::nonNull)
            .toList();
    }
}
