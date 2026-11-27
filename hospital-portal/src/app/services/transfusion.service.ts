import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type AboGroup = 'A' | 'B' | 'AB' | 'O';
export type RhFactor = 'POSITIVE' | 'NEGATIVE';
export type AntibodyScreenResult = 'NEGATIVE' | 'POSITIVE' | 'NOT_DONE';

export type BloodProductType =
  'WHOLE_BLOOD' | 'PACKED_RED_CELLS' | 'FRESH_FROZEN_PLASMA' | 'PLATELETS' | 'CRYOPRECIPITATE';

export type BloodUnitStatus =
  'AVAILABLE' | 'CROSSMATCHED' | 'ISSUED' | 'TRANSFUSED' | 'RETURNED' | 'DISCARDED' | 'EXPIRED';

export type TransfusionRequestStatus =
  'REQUESTED' | 'CROSSMATCHED' | 'ISSUED' | 'COMPLETED' | 'CANCELLED';

export type TransfusionUrgency = 'ROUTINE' | 'URGENT' | 'EMERGENCY';

export type TransfusionAdministrationStatus = 'IN_PROGRESS' | 'COMPLETED' | 'STOPPED';

export type TransfusionReactionType =
  | 'FEBRILE_NON_HEMOLYTIC'
  | 'ACUTE_HEMOLYTIC'
  | 'DELAYED_HEMOLYTIC'
  | 'ALLERGIC'
  | 'ANAPHYLACTIC'
  | 'TACO'
  | 'TRALI'
  | 'SEPTIC'
  | 'HYPOTENSIVE'
  | 'OTHER';

export type TransfusionReactionSeverity = 'MILD' | 'MODERATE' | 'SEVERE' | 'LIFE_THREATENING';

/** QA D3351: the two levels of life-threatening urgency a prescriber declares. */
export type VitalUrgencyLevel = 'UVI' | 'UV';

export type AntibodyWorkupStatus = 'IDENTIFIED' | 'REFERRED';

export interface AntibodyWorkup {
  id: string;
  status: AntibodyWorkupStatus;
  antibodySpecificities: string | null;
  referralCentre: string | null;
  decisionReason: string | null;
  transfusionRequestId: string | null;
  recordedByDisplay: string | null;
  recordedAt: string;
  notes: string | null;
  /** The hospital that made this record. */
  hospitalName?: string | null;
  /** Made at another hospital: only the antibody, the date and the hospital's name are filled. */
  otherHospital?: boolean;
}

/**
 * QA D3351: the permanent allo-immunisation flag and what the biologists recorded. The flag follows the
 * patient: it may rest on another hospital's record, of which only the antibody, the date and the hospital's
 * name are sent.
 */
export interface AntibodyStatusResponse {
  patientId: string;
  alloImmunised: boolean;
  positiveScreenAt: string | null;
  positiveScreenHospitalName?: string | null;
  identified: boolean;
  antibodySpecificities: string | null;
  identifiedHospitalName?: string | null;
  identifiedAt?: string | null;
  /** The other hospitals this safety information comes from; empty when it is all on record here. */
  otherHospitalNames?: string[];
  workups: AntibodyWorkup[];
  /** V380: O RhD positive red cells were released in an emergency for this patient while at risk, anywhere. */
  rhdPositiveExposed?: boolean;
  rhdPositiveExposedAt?: string | null;
  rhdPositiveExposedHospitalName?: string | null;
  /** The bag was given (true), or is out with no administration on record (false). */
  rhdPositiveExposureConfirmed?: boolean | null;
  /** QA D4151: a severe or life-threatening transfusion reaction is on record at this hospital. */
  severeTransfusionReaction?: boolean;
  severeTransfusionReactionType?: TransfusionReactionType | null;
  severeTransfusionReactionAt?: string | null;
}

export interface AntibodyWorkupRequest {
  patientId: string;
  status: AntibodyWorkupStatus;
  antibodySpecificities?: string;
  referralCentre?: string;
  decisionReason?: string;
  transfusionRequestId?: string;
  notes?: string;
}

export interface PatientBloodGroupResponse {
  /** QA D3351: a positive antibody screen is on record here, this one or an earlier one: never cleared. */
  alloImmunised?: boolean;
  /** V380: the patient received O RhD positive red cells in an emergency while at risk. Permanent, any hospital. */
  rhdPositiveExposed?: boolean;
  rhdPositiveExposedAt?: string | null;
  rhdPositiveExposedHospitalName?: string | null;
  /** The bag was given (true), or is out with no administration on record (false). */
  rhdPositiveExposureConfirmed?: boolean | null;
  id: string;
  patientId: string;
  patientName: string;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  antibodyScreen: AntibodyScreenResult;
  antibodyDetail: string | null;
  specimenCollectedAt: string | null;
  performedAt: string;
  expiresAt: string | null;
  performedByName: string | null;
  superseded: boolean;
  /** The SCREEN is still usable for a crossmatch. ABO/Rh never expires. */
  screenCurrent: boolean;
  notes: string | null;
  correctionReason?: string | null;
}

export interface PatientBloodGroupRequest {
  patientId: string;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  antibodyScreen: AntibodyScreenResult;
  antibodyDetail?: string;
  specimenCollectedAt?: string;
  expiresAt?: string;
  /** Required when the ABO/Rh differs from the standing group. */
  correctionReason?: string;
  notes?: string;
}

export interface BloodUnitResponse {
  id: string;
  requestId: string | null;
  unitNumber: string;
  productType: BloodProductType;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  volumeMl: number | null;
  collectedOn: string | null;
  expiresOn: string;
  expired: boolean;
  source: string | null;
  status: BloodUnitStatus;
  discardReason: string | null;
  notes: string | null;
}

export interface BloodUnitRequest {
  unitNumber: string;
  productType: BloodProductType;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  volumeMl?: number;
  collectedOn?: string;
  expiresOn: string;
  source?: string;
  requestId?: string;
  notes?: string;
}

export interface CrossmatchResponse {
  id: string;
  requestId: string;
  bloodUnitId: string;
  unitNumber: string;
  compatible: boolean;
  method: string | null;
  incompatibilityReason: string | null;
  performedByName: string | null;
  performedAt: string;
  expiresAt: string | null;
  /** Compatible AND unexpired — what the issue path actually requires. */
  usable: boolean;
  /**
   * The one pairing this facility's platelet protocol permits and nobody has
   * confirmed was meant: group O platelets to a group B recipient.
   *
   * Advisory only. It does NOT affect `compatible` or `usable` and must never
   * be allowed to — the 2026-08-25 sign-off permits the pairing. It is here so
   * an open clinical question is visible to the blood-bank scientist who can
   * raise it. Optional because it is derived server-side and older responses
   * do not carry it.
   */
  plateletPairingPendingConfirmation?: boolean;
}

export interface CrossmatchRequest {
  bloodUnitId: string;
  compatible: boolean;
  method?: string;
  incompatibilityReason?: string;
  expiresAt?: string;
  /** QA D3351: required for a compatible result on red cells for an allo-immunised patient. */
  sampleCollectedAt?: string;
  antiglobulinMethod?: boolean;
  antigenNegative?: boolean;
  rhKellMatched?: boolean;
}

export interface TransfusionReactionResponse {
  id: string;
  administrationId: string;
  patientId: string;
  patientName: string;
  reactionType: TransfusionReactionType;
  severity: TransfusionReactionSeverity;
  onsetAt: string;
  signsSymptoms: string;
  actionsTaken: string | null;
  unitReturnedToLab: boolean;
  reportedByName: string | null;
  reportedAt: string;
  severe: boolean;
}

export interface TransfusionReactionRequest {
  reactionType: TransfusionReactionType;
  severity: TransfusionReactionSeverity;
  onsetAt: string;
  signsSymptoms: string;
  actionsTaken?: string;
  unitReturnedToLab?: boolean;
}

export interface TransfusionAdministrationResponse {
  /** Running when a recorded death was disputed: not stopped, waiting for an assessment. */
  assessmentDue?: boolean;
  id: string;
  requestId: string;
  bloodUnitId: string;
  unitNumber: string;
  patientId: string;
  patientName: string;
  status: TransfusionAdministrationStatus;
  startedAt: string;
  completedAt: string | null;
  volumeTransfusedMl: number | null;
  administeredByName: string | null;
  verifiedByName: string | null;
  verificationMethod: string | null;
  stopReason: string | null;
  notes: string | null;
  reactions: TransfusionReactionResponse[];
  /** The result on file for this bag; it can come back while the bag is running. */
  crossmatch?: CrossmatchResponse | null;
}

export interface VerifierCandidate {
  userId: string;
  displayName: string;
}

export type EmergencyReleaseType =
  'UNCROSSMATCHED' | 'O_POSITIVE_DECISION' | 'SEROLOGICALLY_INCOMPATIBLE';

/* ── Emergency O RhD positive release authority (V380, owner decision 2026-11-25) ── */

export type OPositiveRiskTier = 'LOW_RISK' | 'HIGH_RISK' | 'KNOWN_ANTIBODY';
export type OPositiveTierReason =
  | 'ADULT_MALE'
  | 'FEMALE_ABOVE_CUTOFF'
  | 'BOY_LOW_RISK_BY_PROTOCOL'
  | 'FEMALE_AT_OR_BELOW_CUTOFF'
  | 'MALE_UNDER_18'
  | 'UNKNOWN_SEX'
  | 'UNKNOWN_AGE'
  | 'NO_ACTIVE_PROTOCOL'
  | 'KNOWN_ANTIBODY';
export type OPositiveDecisionBasis =
  | 'PROTOCOL'
  | 'PHYSICIAN_ORDER'
  | 'BIOLOGIST_AUTHORITY'
  | 'PHYSICIAN_AND_BIOLOGIST'
  | 'AUTHORITY_PENDING';
/** What became of the bag: hung, out with no administration on record, or back unused (nobody exposed). */
export type OPositiveExposureState = 'CONFIRMED' | 'UNCONFIRMED' | 'NOT_GIVEN';
export type OPositiveReviewPriority = 'ROUTINE' | 'URGENT' | 'IMMEDIATE';
export type ONegativeStatus = 'OUT_OF_STOCK' | 'STATED_UNOBTAINABLE' | 'PROTOCOL_FIRST_LINE';
export type AntiDDecision = 'ANTI_D_IMMUNOGLOBULIN' | 'RED_CELL_EXCHANGE' | 'NOT_INDICATED';

/** The hospital's emergency O RhD positive protocol. Inactive means every release is handled as high-risk. */
export interface OPositiveProtocol {
  hospitalId?: string | null;
  active: boolean;
  femaleAgeCutoff: number;
  boysLowRisk: boolean;
  reviewDeadlineHours: number;
}

/** What the O positive authority rule says for one request: the form asks for what the server will require. */
export interface OPositiveRule {
  riskTier: OPositiveRiskTier;
  tierReason: OPositiveTierReason;
  protocolActive: boolean;
  femaleAgeCutoff: number;
  boysLowRisk: boolean;
  reviewDeadlineHours: number;
  /** The low-risk tier: the issuer may be named as deciding. */
  issuerMayDecide: boolean;
  plainIssueAllowed: boolean;
  /** Who can be named as the physician ordering it: never the caller. */
  physicians: VerifierCandidate[];
  /** Who can be named as the biologist, for a patient with a known antibody. */
  biologists: VerifierCandidate[];
}

/** The decision behind one emergency O RhD positive release, and what is still owed on it. */
export interface OPositiveReleaseDecision {
  id: string;
  releaseId: string | null;
  requestId: string;
  bloodUnitId: string;
  unitNumber: string | null;
  patientId: string;
  patientName: string | null;
  riskTier: OPositiveRiskTier;
  tierReason: OPositiveTierReason;
  protocolActive: boolean;
  femaleAgeCutoff: number;
  boysLowRisk: boolean;
  reviewDeadlineHours: number;
  patientSex: 'MALE' | 'FEMALE' | 'UNKNOWN';
  patientAgeYears: number | null;
  negativeStatus: ONegativeStatus;
  decisionBasis: OPositiveDecisionBasis;
  issuedByName: string | null;
  decidedByName: string | null;
  biologistName: string | null;
  verbalOrder: boolean;
  issuedAt: string;
  countersignDueAt: string | null;
  countersignedByName: string | null;
  /** Signed by another physician than the one named as giving the order. */
  countersignCovering?: boolean;
  countersignedAt: string | null;
  countersignOverdue: boolean;
  countersignEscalatedAt: string | null;
  reviewPriority: OPositiveReviewPriority;
  reviewDueAt: string;
  reviewedByName: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  rhdExposure: boolean;
  /** Null when the release flags no exposure. */
  exposureState?: OPositiveExposureState | null;
  /** What became of the bag of THIS release, once written; a bag released again is a new release. */
  disposition?: 'ADMINISTERED' | 'RETURNED_UNUSED' | 'SUPERSEDED' | null;
  /** Known antibody: one person took two of its steps, stating that nobody else could be reached. */
  fourEyesWaived?: boolean;
  followUpRequired: boolean;
  antibodyScreenDueFrom: string | null;
  antibodyScreenDueBy: string | null;
  antibodyScreenDoneAt: string | null;
  antiDDecision: AntiDDecision | null;
  antiDDecidedByName: string | null;
  antiDDecidedAt: string | null;
  patientInformedByName: string | null;
  patientInformedAt: string | null;
  followUpNote: string | null;
}

export interface OPositiveFollowUpRequest {
  antiDDecision?: AntiDDecision;
  patientInformed?: boolean;
  /** Closes the follow-up of a bag that came back unused; the server decides whether it did. */
  bagNotGiven?: boolean;
  note?: string;
}

/* ── Release despite an incompatible crossmatch (V381, owner decision 2026-11-26) ── */

export type IncompatibleReleaseBasis = 'NAMED_PHYSICIAN' | 'AUTHORITY_PENDING';
export type PatientInformedStatus = 'PATIENT_INFORMED' | 'FAMILY_INFORMED' | 'NOT_POSSIBLE';
export type IncompatibleMonitoringOutcome = 'NO_REACTION' | 'REACTION_REPORTED' | 'NOT_TRANSFUSED';
export type IncompatibleAntibodyStatus = 'ANTIBODY_ON_RECORD' | 'NO_ANTIBODY_ON_RECORD';
export type IncompatibleAuthorityTiming = 'BEFORE_ISSUE' | 'AFTER_ISSUE' | 'PENDING';
export type IncompatibleMonitoringCheck =
  | 'BEDSIDE_ABO_CHECK'
  | 'SLOW_START'
  | 'TUBING_KEPT'
  | 'URINE_DIPSTICK'
  | 'FULL_BLOOD_COUNT'
  | 'RETICULOCYTES'
  | 'BILIRUBIN'
  | 'LDH'
  | 'HAPTOGLOBIN'
  | 'CREATININE'
  | 'COAGULATION';

/** The hospital's fallback for an incompatible release with no physician reachable. Off by default. */
export interface IncompatibleReleaseProtocol {
  hospitalId?: string;
  fallbackEnabled: boolean;
  /** The roles that may declare that no physician can be reached. */
  declarerRoles: string[];
  /** Read-only: the deadline of the hospital's emergency release protocol. */
  countersignDeadlineHours?: number;
  /** Read-only: the roles the setting may name. */
  allowedDeclarerRoles?: string[];
}

/** What the incompatible release rule says at this hospital for the caller. The server still decides. */
export interface IncompatibleRule {
  fallbackEnabled: boolean;
  callerMayDeclare: boolean;
  countersignDeadlineHours: number;
}

/** One release despite an incompatible crossmatch, with what is still owed on it. */
export interface IncompatibleReleaseDecision {
  id: string;
  releaseId: string;
  requestId: string;
  bloodUnitId: string;
  unitNumber: string | null;
  patientId: string;
  patientName: string | null;
  decisionBasis: IncompatibleReleaseBasis;
  fallback: boolean;
  countersignDeadlineHours: number;
  issuedByName: string | null;
  authoriserName: string | null;
  declarerRole: string | null;
  unreachableReason: string | null;
  authorityTiming: IncompatibleAuthorityTiming;
  antibodyStatus: IncompatibleAntibodyStatus;
  antibodyDetail: string | null;
  crossmatchFinding: string | null;
  recipientAbo: string | null;
  recipientRh: string | null;
  unitAbo: string;
  unitRh: string | null;
  rhdMatched: boolean;
  rhKellMatched: boolean | null;
  antigenNegative: boolean | null;
  leastIncompatible: boolean;
  candidatesConsidered: number;
  unitChoiceReason: string;
  patientInformed: PatientInformedStatus;
  patientInformedAt: string | null;
  patientInformedByName: string | null;
  issuedAt: string;
  countersignDueAt: string | null;
  countersignedByName: string | null;
  countersignedAt: string | null;
  countersignOverdue: boolean;
  countersignEscalatedAt: string | null;
  monitoringChecklist: IncompatibleMonitoringCheck[];
  monitoringChecksDone: IncompatibleMonitoringCheck[];
  monitoringOutcome: IncompatibleMonitoringOutcome | null;
  monitoringNote: string | null;
  monitoringRecordedByName: string | null;
  monitoringRecordedAt: string | null;
}

export interface IncompatibleMonitoringRequest {
  outcome?: IncompatibleMonitoringOutcome;
  checksDone?: IncompatibleMonitoringCheck[];
  patientInformed?: boolean;
  note?: string;
}

/** One bag's emergency release, as the request detail shows it. */
export interface EmergencyBloodRelease {
  id: string;
  bloodUnitId: string;
  unitNumber: string;
  releaseType: EmergencyReleaseType;
  reason: string | null;
  authoriserName: string | null;
  issuedByName: string | null;
  issuedAt: string;
  antibodyWarning: boolean;
  /** O negative showed in stock and was stated not obtainable in time. */
  negativeUnobtainableStated?: boolean;
  /** The O negative bags shown then, by the number on the bag. */
  negativeUnitsShown?: string | null;
  /** O positive red cells: the tier, who decided and what is still owed (V380). */
  positiveDecision?: OPositiveReleaseDecision | null;
  /** A release despite an incompatible crossmatch: its authority, its bag and its monitoring (V381). */
  incompatibleDecision?: IncompatibleReleaseDecision | null;
}

export interface EmergencyReleaseUnitOption {
  id: string;
  unitNumber: string;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  expiresOn: string;
  /** O positive that the server will release only on a documented decision. */
  decisionRequired: boolean;
  /** Releasing it needs the statement that the O negative shown cannot be obtained in time. */
  statementRequired?: boolean;
}

export interface IncompatibleUnitOption {
  id: string;
  unitNumber: string;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  incompatibilityReason: string | null;
  /** False when the patient's group excludes the bag or it is held for another request. */
  releasable: boolean;
  /** V381: what makes a bag more or less incompatible, as the laboratory recorded it. */
  rhdMatched?: boolean;
  rhKellMatched?: boolean | null;
  antigenNegative?: boolean | null;
  /** V381: no releasable bag ranks better. With no physician reachable only such a bag is released. */
  leastIncompatible?: boolean;
  /** Releasing it needs the statement that the O negative shown cannot be obtained in time. */
  statementRequired?: boolean;
}

/** What the server's emergency rules say for one request. The server still decides on submit. */
export interface EmergencyReleaseOptions {
  antibodyWarning: boolean;
  antibodyDetail: string | null;
  /** O negative the system shows in stock for this patient. It can be wrong: nothing is refused on it alone. */
  negativeStock: EmergencyReleaseUnitOption[];
  units: EmergencyReleaseUnitOption[];
  incompatibleUnits: IncompatibleUnitOption[];
  deciders: VerifierCandidate[];
  authorisers: VerifierCandidate[];
  /** What the O positive authority rule says for this patient (V380). */
  positiveRule?: OPositiveRule | null;
  /** What the incompatible release rule says at this hospital for the caller (V381). */
  incompatibleRule?: IncompatibleRule | null;
}

export interface EmergencyReleaseRequest {
  bloodUnitId: string;
  releaseType: EmergencyReleaseType;
  reason: string;
  /** Absent only for O positive with {@link physicianUnreachable}. */
  authoriserUserId?: string;
  /** The statement that the O negative shown cannot be obtained in time. */
  negativeUnobtainable?: boolean;
  /**
   * No physician can be reached. O positive outside the low-risk tier: the bag goes out, the director is alerted.
   * An incompatible release (V381): accepted only where the hospital enabled it, with {@link unreachableReason}.
   */
  physicianUnreachable?: boolean;
  /** V381, the fallback of an incompatible release: who was called and when. */
  unreachableReason?: string;
  /** V381, every incompatible release: why this bag was chosen. */
  unitChoiceReason?: string;
  /** V381, every incompatible release: whether the patient or the family was told of the risk. */
  patientInformation?: PatientInformedStatus;
  /** The physician's order was verbal (the default): it is countersigned. */
  verbalOrder?: boolean;
  /** A known antibody: the biologist deciding with the physician, when named. */
  biologistUserId?: string;
  /** An incompatible O positive release: the physician who ordered O positive, when not its authoriser. */
  orderingPhysicianUserId?: string;
}

export interface TransfusionAdministrationRequest {
  requestId: string;
  bloodUnitId: string;
  /** The independent second bedside check. Never the caller themselves. */
  verifiedByUserId: string;
  verificationMethod?: string;
  notes?: string;
}

export interface TransfusionRequestResponse {
  /** QA D3351: the patient is allo-immunised here; red cells follow the positive antibody screen rule. */
  alloImmunised?: boolean;
  /** QA D3351: the antibody is identified, or a referral decision serves this request. */
  antibodyWorkupServes?: boolean;
  vitalUrgencyLevel?: VitalUrgencyLevel | null;
  vitalUrgencyReason?: string | null;
  preTransfusionSampleDrawn?: boolean | null;
  /** Existing when a recorded death was disputed: a prescriber reviews it first. */
  heldForReview?: boolean;
  /** QA D1081: the user the server's author check compares with (shared/authorship.service). */
  authorUserId?: string | null;

  id: string;
  patientId: string;
  patientName: string;
  patientMrn: string | null;
  encounterId: string | null;
  productType: BloodProductType;
  unitsRequested: number;
  indication: string;
  urgency: TransfusionUrgency;
  status: TransfusionRequestStatus;
  requestedByName: string | null;
  requestedAt: string;
  requiredBy: string | null;
  cancelReason: string | null;
  notes: string | null;
  bloodGroupId: string | null;
  patientAboGroup: AboGroup | null;
  patientRhFactor: RhFactor | null;
  /** QA r70 D3221: the group a clinician recorded on the chart: declared, never typed. */
  patientDeclaredBloodType?: string | null;
  screenCurrent: boolean;
  units: BloodUnitResponse[];
  crossmatches: CrossmatchResponse[];
  /** Every emergency release on this request, one per bag; absent on a list row. */
  emergencyReleases?: EmergencyBloodRelease[];
  /** V380: the patient received O RhD positive red cells in an emergency while at risk. Permanent, any hospital. */
  rhdPositiveExposed?: boolean;
  rhdPositiveExposedAt?: string | null;
  rhdPositiveExposedHospitalName?: string | null;
  /** The bag was given (true), or is out with no administration on record (false). */
  rhdPositiveExposureConfirmed?: boolean | null;
}

export interface TransfusionRequestRequest {
  patientId: string;
  encounterId?: string;
  productType: BloodProductType;
  unitsRequested: number;
  indication: string;
  urgency?: TransfusionUrgency;
  requiredBy?: string;
  notes?: string;
  /** QA D3351: an emergency for an allo-immunised patient declares the level, why, and the sample drawn first. */
  vitalUrgencyLevel?: VitalUrgencyLevel;
  vitalUrgencyReason?: string;
  preTransfusionSampleDrawn?: boolean;
}

/**
 * Blood bank and transfusion (Tier 2 item 28).
 *
 * <p>Compatibility is decided by the backend and never mirrored here. A
 * client-side ABO check would be a second implementation of the rule that
 * kills people when it drifts from the first, so the UI asks and reports.
 */
@Injectable({ providedIn: 'root' })
export class TransfusionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/transfusions';

  /* ── Type and screen ── */

  recordBloodGroup(req: PatientBloodGroupRequest): Observable<PatientBloodGroupResponse> {
    return this.http.post<PatientBloodGroupResponse>(`${this.baseUrl}/blood-groups`, req);
  }

  /**
   * The patient's current type and screen at this hospital, or `null` when none is on record yet
   * (QA D3352: the server answers 204, not a 404 the browser logs as an error).
   */
  getCurrentBloodGroup(patientId: string): Observable<PatientBloodGroupResponse | null> {
    return this.http.get<PatientBloodGroupResponse | null>(
      `${this.baseUrl}/blood-groups/patient/${patientId}`,
    );
  }

  getBloodGroupHistory(patientId: string): Observable<PatientBloodGroupResponse[]> {
    return this.http.get<PatientBloodGroupResponse[]>(
      `${this.baseUrl}/blood-groups/patient/${patientId}/history`,
    );
  }

  /* ── Units ── */

  receiveUnit(req: BloodUnitRequest): Observable<BloodUnitResponse> {
    return this.http.post<BloodUnitResponse>(`${this.baseUrl}/units`, req);
  }

  listUnits(status?: BloodUnitStatus): Observable<BloodUnitResponse[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<BloodUnitResponse[]>(`${this.baseUrl}/units`, { params });
  }

  /** Colleagues who can be the second check at the bedside (QA CX-008). */
  listVerifierCandidates(): Observable<VerifierCandidate[]> {
    return this.http.get<VerifierCandidate[]>(`${this.baseUrl}/verifier-candidates`);
  }

  listAssignableUnits(): Observable<BloodUnitResponse[]> {
    return this.http.get<BloodUnitResponse[]>(`${this.baseUrl}/units/assignable`);
  }

  discardUnit(unitId: string, reason: string): Observable<BloodUnitResponse> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<BloodUnitResponse>(`${this.baseUrl}/units/${unitId}/discard`, null, {
      params,
    });
  }

  /* ── Positive antibody screen (QA D3351) ── */

  getAntibodyStatus(patientId: string): Observable<AntibodyStatusResponse> {
    return this.http.get<AntibodyStatusResponse>(
      `${this.baseUrl}/antibody-status/patient/${patientId}`,
    );
  }

  recordAntibodyWorkup(req: AntibodyWorkupRequest): Observable<AntibodyStatusResponse> {
    return this.http.post<AntibodyStatusResponse>(`${this.baseUrl}/antibody-workups`, req);
  }

  /* ── Requests ── */

  createRequest(req: TransfusionRequestRequest): Observable<TransfusionRequestResponse> {
    return this.http.post<TransfusionRequestResponse>(`${this.baseUrl}/requests`, req);
  }

  getRequest(requestId: string): Observable<TransfusionRequestResponse> {
    return this.http.get<TransfusionRequestResponse>(`${this.baseUrl}/requests/${requestId}`);
  }

  listRequests(status?: TransfusionRequestStatus): Observable<TransfusionRequestResponse[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<TransfusionRequestResponse[]>(`${this.baseUrl}/requests`, { params });
  }

  listRequestsForPatient(patientId: string): Observable<TransfusionRequestResponse[]> {
    return this.http.get<TransfusionRequestResponse[]>(
      `${this.baseUrl}/requests/patient/${patientId}`,
    );
  }

  cancelRequest(requestId: string, reason: string): Observable<TransfusionRequestResponse> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<TransfusionRequestResponse>(
      `${this.baseUrl}/requests/${requestId}/cancel`,
      null,
      { params },
    );
  }

  /* ── Crossmatch and issue ── */

  recordCrossmatch(requestId: string, req: CrossmatchRequest): Observable<CrossmatchResponse> {
    return this.http.post<CrossmatchResponse>(
      `${this.baseUrl}/requests/${requestId}/crossmatch`,
      req,
    );
  }

  listCrossmatches(requestId: string): Observable<CrossmatchResponse[]> {
    return this.http.get<CrossmatchResponse[]>(`${this.baseUrl}/requests/${requestId}/crossmatch`);
  }

  issueUnit(requestId: string, unitId: string): Observable<BloodUnitResponse> {
    return this.http.post<BloodUnitResponse>(
      `${this.baseUrl}/requests/${requestId}/issue/${unitId}`,
      null,
    );
  }

  emergencyReleaseOptions(requestId: string): Observable<EmergencyReleaseOptions> {
    return this.http.get<EmergencyReleaseOptions>(
      `${this.baseUrl}/requests/${requestId}/emergency-release/options`,
    );
  }

  emergencyRelease(
    requestId: string,
    release: EmergencyReleaseRequest,
  ): Observable<BloodUnitResponse> {
    return this.http.post<BloodUnitResponse>(
      `${this.baseUrl}/requests/${requestId}/emergency-release`,
      release,
    );
  }

  /* ── Emergency O RhD positive release authority (V380) ── */

  getPositiveProtocol(): Observable<OPositiveProtocol> {
    return this.http.get<OPositiveProtocol>(`${this.baseUrl}/o-positive-protocol`);
  }

  setPositiveProtocol(protocol: OPositiveProtocol): Observable<OPositiveProtocol> {
    return this.http.put<OPositiveProtocol>(`${this.baseUrl}/o-positive-protocol`, protocol);
  }

  listPositiveReleases(openOnly: boolean): Observable<OPositiveReleaseDecision[]> {
    const params = new HttpParams().set('openOnly', String(openOnly));
    return this.http.get<OPositiveReleaseDecision[]>(`${this.baseUrl}/o-positive-releases`, {
      params,
    });
  }

  /** `noOtherPersonReachable`: a known antibody's second step taken by the person who took the first. */
  countersignPositiveRelease(
    decisionId: string,
    noOtherPersonReachable = false,
  ): Observable<OPositiveReleaseDecision> {
    return this.http.post<OPositiveReleaseDecision>(
      `${this.baseUrl}/o-positive-releases/${decisionId}/countersign`,
      noOtherPersonReachable ? { noOtherPersonReachable: true } : null,
    );
  }

  reviewPositiveRelease(
    decisionId: string,
    note: string,
    noOtherPersonReachable = false,
  ): Observable<OPositiveReleaseDecision> {
    return this.http.post<OPositiveReleaseDecision>(
      `${this.baseUrl}/o-positive-releases/${decisionId}/review`,
      { note, ...(noOtherPersonReachable ? { noOtherPersonReachable: true } : {}) },
    );
  }

  recordPositiveFollowUp(
    decisionId: string,
    followUp: OPositiveFollowUpRequest,
  ): Observable<OPositiveReleaseDecision> {
    return this.http.post<OPositiveReleaseDecision>(
      `${this.baseUrl}/o-positive-releases/${decisionId}/follow-up`,
      followUp,
    );
  }

  /* ── Release despite an incompatible crossmatch (V381) ── */

  getIncompatibleProtocol(): Observable<IncompatibleReleaseProtocol> {
    return this.http.get<IncompatibleReleaseProtocol>(
      `${this.baseUrl}/incompatible-release-protocol`,
    );
  }

  setIncompatibleProtocol(
    protocol: Pick<IncompatibleReleaseProtocol, 'fallbackEnabled' | 'declarerRoles'>,
  ): Observable<IncompatibleReleaseProtocol> {
    return this.http.put<IncompatibleReleaseProtocol>(
      `${this.baseUrl}/incompatible-release-protocol`,
      protocol,
    );
  }

  listIncompatibleReleases(openOnly: boolean): Observable<IncompatibleReleaseDecision[]> {
    const params = new HttpParams().set('openOnly', String(openOnly));
    return this.http.get<IncompatibleReleaseDecision[]>(`${this.baseUrl}/incompatible-releases`, {
      params,
    });
  }

  countersignIncompatibleRelease(decisionId: string): Observable<IncompatibleReleaseDecision> {
    return this.http.post<IncompatibleReleaseDecision>(
      `${this.baseUrl}/incompatible-releases/${decisionId}/countersign`,
      null,
    );
  }

  recordIncompatibleMonitoring(
    decisionId: string,
    monitoring: IncompatibleMonitoringRequest,
  ): Observable<IncompatibleReleaseDecision> {
    return this.http.post<IncompatibleReleaseDecision>(
      `${this.baseUrl}/incompatible-releases/${decisionId}/monitoring`,
      monitoring,
    );
  }

  /* ── Bedside ── */

  startAdministration(
    req: TransfusionAdministrationRequest,
  ): Observable<TransfusionAdministrationResponse> {
    return this.http.post<TransfusionAdministrationResponse>(
      `${this.baseUrl}/administrations`,
      req,
    );
  }

  completeAdministration(
    administrationId: string,
    volumeMl?: number,
  ): Observable<TransfusionAdministrationResponse> {
    let params = new HttpParams();
    if (volumeMl != null) params = params.set('volumeMl', String(volumeMl));
    return this.http.post<TransfusionAdministrationResponse>(
      `${this.baseUrl}/administrations/${administrationId}/complete`,
      null,
      { params },
    );
  }

  stopAdministration(
    administrationId: string,
    reason: string,
  ): Observable<TransfusionAdministrationResponse> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<TransfusionAdministrationResponse>(
      `${this.baseUrl}/administrations/${administrationId}/stop`,
      null,
      { params },
    );
  }

  listAdministrationsForPatient(
    patientId: string,
  ): Observable<TransfusionAdministrationResponse[]> {
    return this.http.get<TransfusionAdministrationResponse[]>(
      `${this.baseUrl}/administrations/patient/${patientId}`,
    );
  }

  recordReaction(
    administrationId: string,
    req: TransfusionReactionRequest,
  ): Observable<TransfusionReactionResponse> {
    return this.http.post<TransfusionReactionResponse>(
      `${this.baseUrl}/administrations/${administrationId}/reaction`,
      req,
    );
  }

  listReactionsForPatient(patientId: string): Observable<TransfusionReactionResponse[]> {
    return this.http.get<TransfusionReactionResponse[]>(
      `${this.baseUrl}/reactions/patient/${patientId}`,
    );
  }
}
