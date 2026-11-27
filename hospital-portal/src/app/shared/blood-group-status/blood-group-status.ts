/**
 * QA D3901 (r77, High): the patient's blood group as the server shows it — the group typed on the chart
 * (`declared`, never verified) beside the laboratory's current grouping at this hospital (`confirmed`, with where
 * and when). Built by the server's PatientBloodGroupStatusService; every response that carries the typed group
 * carries this beside it (BloodGroupDisplayGuardTest).
 */
export interface BloodGroupStatus {
  declared?: string | null;
  confirmed?: string | null;
  confirmedAt?: string | null;
  confirmedHospitalName?: string | null;
  /** Both exist and differ: the confirmed group is shown, with a warning. */
  discrepancy?: boolean;
  /** A positive antibody screen or an identified antibody, at any hospital. Null when the read did not look. */
  alloImmunised?: boolean | null;
  /** The antibody identified (« anti-E »); null while a positive screen is not yet identified. */
  antibodySpecificities?: string | null;
  /** V380: received O RhD positive red cells in an emergency while at risk, at any hospital. Permanent. */
  rhdPositiveExposed?: boolean | null;
  rhdPositiveExposedAt?: string | null;
  /** The bag was given (true), or is out with no administration on record (false). */
  rhdPositiveExposureConfirmed?: boolean | null;
  /** QA D4151: a severe or life-threatening transfusion reaction is on record at this hospital. Permanent. */
  severeTransfusionReaction?: boolean | null;
  /** The newest such reaction: its type (ACUTE_HEMOLYTIC, ...) and when it began. */
  severeTransfusionReactionType?: string | null;
  severeTransfusionReactionAt?: string | null;
  /** QA D4151 review: the hospital that holds the reaction's record (it follows the patient). */
  severeTransfusionReactionHospitalName?: string | null;
}

/** What a screen shows: the confirmed group when there is one, else the declared one (labelled so). */
export function shownBloodGroup(
  status: BloodGroupStatus | null | undefined,
  declared?: string | null,
): string | null {
  return status?.confirmed || status?.declared || declared || null;
}
