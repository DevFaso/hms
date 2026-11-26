package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.IncompatibleMonitoringCheck;
import com.example.hms.enums.IncompatibleMonitoringOutcome;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * What a clinician records after a bag went out despite an incompatible crossmatch: the checks done, what the
 * monitoring found (the haemovigilance prompt), and that the patient was told when there was no time before.
 * An empty body is refused at the edge, on the outcome.
 */
@com.example.hms.payload.dto.OneOf(fields = {"outcome", "checksDone", "patientInformed", "note"},
    message = "{transfusion.incompatible.monitoring.nothingRecorded}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncompatibleMonitoringRequestDTO {

    /** Closes the monitoring: no reaction, or a reaction recorded and declared. */
    private IncompatibleMonitoringOutcome outcome;
    /** The checks of the rule's checklist done so far; added to those already recorded. */
    private List<IncompatibleMonitoringCheck> checksDone;
    /** The patient, not told at release, has now been told. */
    private Boolean patientInformed;
    private String note;
}
