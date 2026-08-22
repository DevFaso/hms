package com.example.hms.service.scheduling;

import com.example.hms.payload.dto.scheduling.VisitTypeRequestDTO;
import com.example.hms.payload.dto.scheduling.VisitTypeResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Visit-type catalog CRUD (P2 #11).
 *
 * <p>The slot foundation (PR #459) shipped the model and its inventory
 * operations with no way to put a row into {@code visit_types} — the parent
 * table of the whole chain, so {@code POST /slots/generate} could only ever
 * answer {@code slotsCreated=0}. This is the missing populator.
 */
public interface VisitTypeService {

    List<VisitTypeResponseDTO> list(boolean includeInactive);

    VisitTypeResponseDTO create(VisitTypeRequestDTO request);

    VisitTypeResponseDTO update(UUID id, VisitTypeRequestDTO request);

    VisitTypeResponseDTO deactivate(UUID id);

    VisitTypeResponseDTO reactivate(UUID id);
}
