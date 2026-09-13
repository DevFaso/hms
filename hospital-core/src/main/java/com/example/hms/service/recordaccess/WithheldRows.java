package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.RestrictedRowsDTO;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * E9 #64 — the rows one read withheld under decision D3, tallied per
 * recording hospital and department so the chart can say <i>Dossier
 * restreint (hôpital, département, n)</i> and offer break-the-glass, instead
 * of leaving a silent gap a clinician cannot tell from "nothing recorded".
 *
 * <p>{@link #admit} is {@link CrossHospitalRows#maySurface} with a memory: the
 * same rule, and a note of every row it refuses. A local row is never noted
 * (D3 does not apply to it) and an unlocked read notes nothing (nothing was
 * withheld), so in-hospital behaviour is unchanged and the summary is empty.
 *
 * <p>One instance per read. Not thread-safe, never shared.
 */
public final class WithheldRows {

    private record Key(UUID hospitalId, String departmentName) {
    }

    private final Map<Key, Long> counts = new LinkedHashMap<>();
    private final Map<UUID, String> hospitalNames = new HashMap<>();

    /** The D3 rule; a refused row is counted under its hospital and department. */
    public boolean admit(Hospital rowHospital, Department department, UUID actingHospitalId,
                         SensitivityCategory category, boolean unlocked) {
        if (CrossHospitalRows.maySurface(rowHospital, actingHospitalId, category, unlocked)) {
            return true;
        }
        // Refused only when the row is foreign, so rowHospital is non-null here.
        UUID hospitalId = rowHospital.getId();
        hospitalNames.putIfAbsent(hospitalId, rowHospital.getName());
        counts.merge(new Key(hospitalId, department != null ? department.getName() : null), 1L, Long::sum);
        return false;
    }

    /** The rule without a break-the-glass session (the storyboard's problems). */
    public boolean admit(Hospital rowHospital, Department department, UUID actingHospitalId,
                         SensitivityCategory category) {
        return admit(rowHospital, department, actingHospitalId, category, false);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    /** One line per (hospital, department), hospitals alphabetical, rows without a department first. */
    public List<RestrictedRowsDTO> summaries() {
        return counts.entrySet().stream()
            .map(entry -> RestrictedRowsDTO.builder()
                .hospitalId(entry.getKey().hospitalId())
                .hospitalName(hospitalNames.get(entry.getKey().hospitalId()))
                .departmentName(entry.getKey().departmentName())
                .count(entry.getValue())
                .build())
            .sorted(Comparator
                .comparing(RestrictedRowsDTO::getHospitalName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RestrictedRowsDTO::getDepartmentName, Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
    }
}
