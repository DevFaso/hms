import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';

/* ── DTOs matching backend PatientPortalController ── */

export interface PatientProfileDTO {
  patientId: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: string;
  email: string;
  phone: string;
  address: string;
  emergencyContactName: string;
  emergencyContactPhone: string;
  emergencyContactRelationship: string;
  insuranceProvider: string;
  insuranceMemberId: string;
  insurancePlan: string;
  bloodType: string;
  allergies: string[];
  preferredLanguage: string;
  primaryCareProvider: string;
  facility: string;
  memberSince: string;
  profileImageUrl: string | null;
  hospitalId?: string;
  hospitalName?: string;
}

export interface HealthSummaryDTO {
  profile: PatientProfileDTO;
  recentLabResults: LabResultSummary[];
  currentMedications: MedicationSummary[];
  latestVitals: VitalSignSummary[];
  immunizations: ImmunizationSummary[];
  allergies: string[];
  activeDiagnoses: string[];
}

export interface LabResultSummary {
  id: string;
  testName: string;
  result: string;
  referenceRange: string;
  status: string;
  collectedDate: string;
  isAbnormal: boolean;
  unit: string;
  orderedBy: string;
  performedBy: string;
  category: string;
  notes: string;
  resultedAt: string;
}

/** Raw shape returned by backend PatientLabResultResponseDTO */
interface LabResultApiResponse {
  id: string;
  testName: string;
  value: string;
  unit: string;
  referenceRange: string;
  status: string;
  collectedAt: string;
  resultedAt: string;
  orderedBy: string;
  performedBy: string;
  category: string;
  notes: string;
}

export interface MedicationSummary {
  id: string;
  medicationName: string;
  dosage: string;
  frequency: string;
  prescribedBy: string;
  startDate: string;
  status: string;
  route: string;
  endDate: string;
  indication: string;
  instructions: string;

  /** Refills the prescriber authorized when writing the prescription. */
  refillsAllowed?: number;
  /** Refills still available under that original authorization. */
  refillsRemaining?: number;
  /** Fills already released by an approved refill request. */
  refillsUsed?: number;
  /** False once the prescription is cancelled, discontinued, or was never signed. */
  refillable?: boolean;
  /** Where the patient's most recent request stands; absent if they never asked. */
  refillRequestStatus?: RefillRequestStatus;
  refillRequestUpdatedAt?: string;
  /** The provider's note — the reason for a hold or a denial. */
  refillProviderNotes?: string;
  /** True while REQUESTED or PAUSED: a decision is still outstanding. */
  refillRequestOpen?: boolean;
}

export type RefillRequestStatus =
  'REQUESTED' | 'PAUSED' | 'APPROVED' | 'DENIED' | 'DISPENSED' | 'CANCELLED';

export interface VitalSignSummary {
  id: string;
  type: string;
  value: string;
  unit: string;
  recordedAt: string;
  source: string;
  groupId?: string;
}

/** Raw shape returned by the backend PatientVitalSignResponseDTO. */
interface PatientVitalSignRaw {
  id: string;
  source: string;
  recordedAt: string;
  temperatureCelsius?: number | null;
  heartRateBpm?: number | null;
  respiratoryRateBpm?: number | null;
  systolicBpMmHg?: number | null;
  diastolicBpMmHg?: number | null;
  spo2Percent?: number | null;
  bloodGlucoseMgDl?: number | null;
  weightKg?: number | null;
}

/** Backend health-summary shape before fields are mapped. */
interface HealthSummaryRaw {
  profile: PatientProfileDTO;
  recentLabResults: LabResultApiResponse[];
  currentMedications: MedicationSummary[];
  recentVitals: PatientVitalSignRaw[];
  immunizations: ImmunizationApiResponse[];
  allergies: string[];
  activeDiagnoses: string[];
  chronicConditions: string[];
}

/**
 * Expand one composite vitals record into individual VitalSignSummary rows —
 * one per non-null reading.
 */
function flattenVitals(raw: PatientVitalSignRaw[]): VitalSignSummary[] {
  const fields: {
    key: keyof PatientVitalSignRaw;
    type: string;
    unit: string;
    format?: (r: PatientVitalSignRaw) => string;
  }[] = [
    {
      key: 'systolicBpMmHg',
      type: 'BLOOD_PRESSURE',
      unit: 'mmHg',
      format: (r) => `${r.systolicBpMmHg}/${r.diastolicBpMmHg}`,
    },
    { key: 'heartRateBpm', type: 'HEART_RATE', unit: 'bpm' },
    { key: 'temperatureCelsius', type: 'TEMPERATURE', unit: '°C' },
    { key: 'respiratoryRateBpm', type: 'RESPIRATORY_RATE', unit: 'breaths/min' },
    { key: 'spo2Percent', type: 'OXYGEN_SATURATION', unit: '%' },
    { key: 'bloodGlucoseMgDl', type: 'BLOOD_GLUCOSE', unit: 'mg/dL' },
    { key: 'weightKg', type: 'WEIGHT', unit: 'kg' },
  ];

  const entries: VitalSignSummary[] = [];
  for (const r of raw) {
    const base = { recordedAt: r.recordedAt, source: r.source ?? 'Nurse Station' };
    for (const f of fields) {
      if (r[f.key] != null) {
        entries.push({
          id: `${r.id}-${f.type.toLowerCase()}`,
          type: f.type,
          value: f.format ? f.format(r) : `${r[f.key]}`,
          unit: f.unit,
          groupId: r.id,
          ...base,
        });
      }
    }
  }
  return entries;
}

export interface ImmunizationSummary {
  id: string;
  vaccineName: string;
  dateAdministered: string;
  provider: string;
  status: string;
  site: string;
  route: string;
  lotNumber: string;
  manufacturer: string;
  doseNumber: number | null;
  totalDosesInSeries: number | null;
}

/** Raw shape returned by backend ImmunizationResponseDTO */
interface ImmunizationApiResponse {
  id: string;
  vaccineDisplay: string;
  vaccineType: string;
  administrationDate: string;
  administeredByName: string;
  status: string;
  doseNumber: number;
  totalDosesInSeries: number;
  manufacturer: string;
  lotNumber: string;
  site: string;
  route: string;
}

export interface PortalAppointment {
  id: string;
  date: string;
  startTime: string;
  endTime: string;
  /** Unformatted "HH:mm" values — needed to prefill the reschedule form. */
  rawStartTime: string;
  rawEndTime: string;
  providerName: string;
  department: string;
  reason: string;
  status: string;
  location: string;
  preCheckedIn: boolean;
}

/** Raw shape returned by backend AppointmentResponseDTO */
interface AppointmentApiResponse {
  id: string;
  appointmentDate: string;
  startTime: string;
  endTime: string;
  staffName: string;
  departmentName: string;
  reason: string;
  status: string;
  hospitalName: string;
  hospitalAddress: string;
  notes: string;
  preCheckedIn: boolean;
  preCheckinTimestamp: string | null;
}

/** Format "HH:mm:ss" or "HH:mm" → "h:mm AM/PM" */
function formatTime(raw: string | null | undefined): string {
  if (!raw) return '';
  const parts = raw.split(':');
  if (parts.length < 2) return raw;
  let h = Number.parseInt(parts[0], 10);
  const m = parts[1];
  const ampm = h >= 12 ? 'PM' : 'AM';
  if (h === 0) h = 12;
  else if (h > 12) h -= 12;
  return `${h}:${m} ${ampm}`;
}

/** Truncate "HH:mm:ss" → "HH:mm" (the shape `<input type="time">` expects). */
function rawTime(raw: string | null | undefined): string {
  if (!raw) return '';
  const parts = raw.split(':');
  return parts.length < 2 ? raw : `${parts[0]}:${parts[1]}`;
}

function mapAppointment(a: AppointmentApiResponse): PortalAppointment {
  return {
    id: a.id,
    date: a.appointmentDate ?? '',
    startTime: formatTime(a.startTime),
    endTime: formatTime(a.endTime),
    rawStartTime: rawTime(a.startTime),
    rawEndTime: rawTime(a.endTime),
    providerName: a.staffName ?? '',
    department: a.departmentName ?? '',
    reason: a.reason ?? '',
    status: a.status ?? '',
    location: a.hospitalName ?? '',
    preCheckedIn: a.preCheckedIn ?? false,
  };
}

/**
 * Determine if a lab result is abnormal by comparing to the reference range.
 * Reference ranges are typically formatted as "low-high" (e.g., "70-100").
 */
function isLabResultAbnormal(
  value: string | null | undefined,
  refRange: string | null | undefined,
): boolean {
  if (!value || !refRange) return false;
  const num = Number.parseFloat(value);
  if (Number.isNaN(num)) return false;
  const match = /([\d.]+)\s*[-–]\s*([\d.]+)/.exec(refRange);
  if (!match) return false;
  const low = Number.parseFloat(match[1]);
  const high = Number.parseFloat(match[2]);
  return num < low || num > high;
}

function mapLabResult(l: LabResultApiResponse): LabResultSummary {
  return {
    id: l.id,
    testName: l.testName ?? '',
    result: l.value ?? '',
    referenceRange: l.referenceRange ?? '',
    status: l.status ?? '',
    collectedDate: l.collectedAt ?? '',
    isAbnormal: isLabResultAbnormal(l.value, l.referenceRange),
    unit: l.unit ?? '',
    orderedBy: l.orderedBy ?? '',
    performedBy: l.performedBy ?? '',
    category: l.category ?? '',
    notes: l.notes ?? '',
    resultedAt: l.resultedAt ?? '',
  };
}

function mapHealthSummary(d: HealthSummaryRaw | null | undefined): HealthSummaryDTO {
  return {
    profile: d?.profile ?? ({} as PatientProfileDTO),
    latestVitals: flattenVitals(d?.recentVitals ?? []),
    recentLabResults: (d?.recentLabResults ?? []).map(mapLabResult),
    currentMedications: d?.currentMedications ?? [],
    immunizations: (d?.immunizations ?? []).map((im) => ({
      id: im.id,
      vaccineName: im.vaccineDisplay ?? im.vaccineType ?? '',
      dateAdministered: im.administrationDate ?? '',
      provider: im.administeredByName ?? '',
      status: im.status ?? '',
      site: im.site ?? '',
      route: im.route ?? '',
      lotNumber: im.lotNumber ?? '',
      manufacturer: im.manufacturer ?? '',
      doseNumber: im.doseNumber ?? null,
      totalDosesInSeries: im.totalDosesInSeries ?? null,
    })),
    allergies: d?.allergies ?? [],
    activeDiagnoses: d?.activeDiagnoses ?? d?.chronicConditions ?? [],
  };
}

export interface PortalEncounter {
  id: string;
  date: string;
  type: string;
  providerName: string;
  department: string;
  chiefComplaint: string;
  diagnosisSummary: string;
  status: string;
  notes: string;
  hospitalName: string;
  appointmentReason: string;
}

/** Raw shape returned by the backend EncounterResponseDTO */
interface EncounterApiResponse {
  id: string;
  encounterDate: string;
  encounterType: string;
  staffName: string;
  staffFullName: string;
  departmentName: string;
  hospitalName: string;
  appointmentReason: string;
  notes: string;
  status: string;
  note?: {
    chiefComplaint?: string;
    assessment?: string;
  };
}

export interface PortalInvoice {
  id: string;
  invoiceNumber: string;
  date: string;
  dueDate: string;
  amount: number;
  balance: number;
  status: string;
  facility: string;
  description: string;
}

export interface CareTeamMember {
  name: string;
  role: string;
  specialty: string;
  phone: string;
  email: string;
  isPrimary: boolean;
}

export interface CareTeamDTO {
  members: CareTeamMember[];
}

export interface PortalPrescription {
  id: string;
  medicationName: string;
  dosage: string;
  frequency: string;
  duration: string;
  notes: string;
  prescribedBy: string;
  prescribedDate: string;
  status: string;
}

/** Raw shape returned by backend PrescriptionResponseDTO. */
interface PrescriptionApiResponse {
  id: string;
  medicationName: string;
  medicationDisplayName?: string;
  dosage: string;
  frequency: string;
  duration: string;
  notes: string;
  staffFullName: string;
  status: string;
  createdAt: string;
}

export interface AfterVisitSummary {
  id: string;
  encounterDate: string;
  encounterType: string;
  providerName: string;
  hospitalName: string;
  diagnoses: string[];
  treatmentSummary: string;
  disposition: string;
  dischargeCondition: string;
  followUpInstructions: string;
  activityRestrictions: string;
  dietInstructions: string;
  woundCareInstructions: string;
  warningSigns: string;
  patientEducation: string;
  additionalNotes: string;
  followUpDate: string | null;
  medications: { name: string; dosage: string; frequency: string }[];
  status: string;
}

/** Raw shape returned by backend DischargeSummaryResponseDTO */
interface DischargeSummaryApiResponse {
  id: string;
  encounterId: string;
  encounterType: string;
  patientName: string;
  hospitalName: string;
  dischargingProviderName: string;
  dischargeDate: string;
  dischargeTime: string;
  disposition: string;
  dischargeDiagnosis: string;
  hospitalCourse: string;
  dischargeCondition: string;
  activityRestrictions: string;
  dietInstructions: string;
  woundCareInstructions: string;
  followUpInstructions: string;
  warningSigns: string;
  patientEducationProvided: string;
  medicationReconciliation: { medicationName: string; dosage: string; frequency: string }[];
  followUpAppointments: { department: string; scheduledDate: string }[];
  isFinalized: boolean;
  additionalNotes: string;
}

export interface PatientConsent {
  id: string;
  fromHospitalId: string;
  fromHospitalName: string;
  toHospitalId: string;
  toHospitalName: string;
  purpose: string;
  grantedAt: string;
  expiresAt: string;
  status: string;
}

/** Raw shape from backend PatientConsentResponseDTO */
interface ConsentApiResponse {
  id: string;
  consentGiven: boolean;
  consentTimestamp: string;
  consentExpiration: string;
  purpose: string;
  patientId: string;
  fromHospital: { id: string; name: string };
  toHospital: { id: string; name: string };
}

export interface PortalConsentRequest {
  fromHospitalId: string;
  toHospitalId: string;
  purpose: string;
  consentExpiration: string;
}

export interface AccessLogEntry {
  id: string;
  accessedBy: string;
  accessedByRole: string;
  accessType: string;
  resourceAccessed: string;
  accessedAt: string;
  ipAddress: string;
}

export interface MedicationRefill {
  id: string;
  prescriptionId: string;
  medicationName: string;
  preferredPharmacy: string;
  status: string;
  requestedAt: string;
  updatedAt: string | null;
  notes: string;
}

export interface RefillRequest {
  prescriptionId: string;
  preferredPharmacy: string;
  notes: string;
}

export interface HomeVitalReading {
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  heartRateBpm?: number;
  respiratoryRateBpm?: number;
  spo2Percent?: number;
  temperatureCelsius?: number;
  bloodGlucoseMgDl?: number;
  weightKg?: number;
  bodyPosition?: string;
  notes?: string;
}

export interface CancelAppointmentRequest {
  appointmentId: string;
  reason: string;
}

export interface RescheduleAppointmentRequest {
  appointmentId: string;
  newDate: string;
  newStartTime: string;
  newEndTime: string;
  reason: string;
}

// ── MVP 4: Pre-Visit Questionnaires & Pre-Check-In ────────────────

export interface QuestionnaireQuestion {
  id: string;
  text: string;
  type: 'YES_NO' | 'SCALE' | 'TEXT' | 'MULTI_CHOICE' | 'NUMBER';
  required?: boolean;
  options?: string[];
  min?: number;
  max?: number;
}

export interface QuestionnaireDTO {
  id: string;
  title: string;
  description: string | null;
  questions: string; // JSON string of QuestionnaireQuestion[]
  version: number;
  departmentId: string | null;
  departmentName: string | null;
}

export interface QuestionnaireSubmission {
  questionnaireId: string;
  responses: string; // JSON string of answers
}

export interface PreCheckInRequest {
  appointmentId: string;
  phoneNumber?: string;
  email?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  state?: string;
  zipCode?: string;
  country?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  emergencyContactRelationship?: string;
  insuranceProvider?: string;
  insuranceMemberId?: string;
  insurancePlan?: string;
  questionnaireResponses?: QuestionnaireSubmission[];
  consentAcknowledged?: boolean;
}

export interface PreCheckInResponse {
  appointmentId: string;
  appointmentStatus: string;
  preCheckedIn: boolean;
  preCheckinTimestamp: string;
  questionnaireResponsesSubmitted: number;
  demographicsUpdated: boolean;
}

// ── Self-Scheduling Interfaces ─────────────────────────────────────

export interface BookAppointmentRequest {
  hospitalId: string;
  departmentId: string;
  staffId?: string;
  date: string; // yyyy-MM-dd
  startTime: string; // HH:mm
  endTime?: string; // HH:mm — defaults to startTime + 30 min
  reason?: string;
  notes?: string;
}

export interface SchedulingHospital {
  id: string;
  name: string;
  address: string;
}

export interface SchedulingDepartment {
  id: string;
  name: string;
}

export interface SchedulingProvider {
  id: string;
  name: string;
  fullName?: string;
  role?: string;
}

export interface PatientPaymentRequest {
  amount: number;
  paymentMethod: string;
  transactionReference?: string;
  notes?: string;
}

export interface ProxyResponse {
  id: string;
  grantorPatientId: string;
  grantorName: string;
  proxyUserId: string;
  proxyUsername: string;
  proxyDisplayName: string;
  relationship: string;
  status: string;
  permissions: string;
  expiresAt: string | null;
  revokedAt: string | null;
  notes: string | null;
  createdAt: string;
}

export interface ProxyGrantRequest {
  proxyUsername: string;
  relationship: string;
  permissions: string;
  expiresAt?: string;
  notes?: string;
}

/** One education resource assigned to the patient, joined with their progress. */
export interface PatientEducationItem {
  progressId: string;
  comprehensionStatus: string;
  progressPercentage: number | null;
  startedAt: string | null;
  completedAt: string | null;
  lastAccessedAt: string | null;
  rating: number | null;
  feedback: string | null;
  needsClarification: boolean | null;
  clarificationRequest: string | null;
  confirmedUnderstanding: boolean | null;
  resourceId: string;
  title: string;
  description: string | null;
  resourceType: string;
  category: string;
  contentUrl: string | null;
  textContent: string | null;
  thumbnailUrl: string | null;
  videoUrl: string | null;
  estimatedDuration: number | null;
  tags: string[];
  primaryLanguage: string | null;
  isWarningSignContent: boolean | null;
}

/** Patient-writable subset — comprehension status is derived server-side. */
export interface PatientEducationProgressUpdate {
  progressPercentage?: number;
  rating?: number;
  feedback?: string;
  confirmedUnderstanding?: boolean;
  needsClarification?: boolean;
  clarificationRequest?: string;
}

export interface PatientEducationQuestion {
  id: string;
  resourceId: string | null;
  questionText: string;
  isUrgent: boolean | null;
  isAnswered: boolean | null;
  answerText: string | null;
  answeredAt: string | null;
  requiresInPersonDiscussion: boolean | null;
  appointmentScheduled: boolean | null;
  createdAt: string;
}

export interface PatientEducationQuestionSubmit {
  resourceId?: string;
  questionText: string;
  isUrgent?: boolean;
  requiresInPersonDiscussion?: boolean;
}

export type PatientDocumentType =
  | 'LAB_RESULT'
  | 'IMAGING_REPORT'
  | 'DISCHARGE_SUMMARY'
  | 'REFERRAL_LETTER'
  | 'PRESCRIPTION'
  | 'INSURANCE_DOCUMENT'
  | 'INVOICE'
  | 'IMMUNIZATION_RECORD'
  | 'OTHER';

export interface PatientDocumentResponse {
  id: string;
  patientId: string;
  uploadedByUserId: string;
  uploadedByDisplayName: string;
  documentType: PatientDocumentType;
  displayName: string;
  fileUrl: string;
  mimeType: string;
  fileSizeBytes: number;
  checksumSha256: string | null;
  collectionDate: string | null;
  notes: string | null;
  createdAt: string;
}

export interface PortalNotification {
  id: string;
  message: string;
  type: string | null;
  read: boolean;
  recipientUsername: string;
  createdAt: string;
}

export interface NotificationPreference {
  id: string;
  userId: string;
  notificationType: string;
  channel: string;
  enabled: boolean;
}

export interface NotificationPreferenceUpdate {
  notificationType: string;
  channel: string;
  enabled: boolean;
}

// ── Medical & Family History ──────────────────────────────────────

export interface PatientDiagnosisSummary {
  id: string;
  description: string;
  icdCode: string | null;
  status: string;
  diagnosedAt: string;
  diagnosedByName: string | null;
}

export interface SurgicalHistorySummary {
  id: string;
  patientId: string;
  hospitalId: string | null;
  hospitalName: string | null;
  procedureCode: string | null;
  procedureDisplay: string;
  procedureDate: string | null;
  outcome: string | null;
  performedBy: string | null;
  location: string | null;
  notes: string | null;
}

export interface FamilyHistorySummary {
  id: string;
  relationship: string;
  relativeName: string | null;
  relativeGender: string | null;
  relativeLiving: boolean | null;
  relativeAge: number | null;
  relativeAgeAtDeath: number | null;
  causeOfDeath: string | null;
  conditionCode: string | null;
  conditionDisplay: string | null;
  conditionCategory: string | null;
  ageAtOnset: number | null;
  severity: string | null;
  notes: string | null;
  active: boolean | null;
}

export interface SocialHistorySummary {
  id: string;
  tobaccoUse: boolean | null;
  tobaccoType: string | null;
  tobaccoPacksPerDay: number | null;
  tobaccoYearsUsed: number | null;
  tobaccoQuitDate: string | null;
  tobaccoNotes: string | null;
  alcoholUse: boolean | null;
  alcoholFrequency: string | null;
  alcoholDrinksPerWeek: number | null;
  alcoholBingeDrinking: boolean | null;
  alcoholNotes: string | null;
  recreationalDrugUse: boolean | null;
  exerciseFrequency: string | null;
  exerciseType: string | null;
  occupation: string | null;
  employmentStatus: string | null;
  maritalStatus: string | null;
  active: boolean | null;
}

interface ApiWrapper<T> {
  data: T;
  success: boolean;
}

type ApiMaybeWrapped<T> = ApiWrapper<T> | T;

interface PageWrapper<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class PatientPortalService {
  private readonly http = inject(HttpClient);
  private readonly base = '/me/patient';

  private unwrapData<T>(response: ApiMaybeWrapped<T> | null | undefined): T | null | undefined {
    if (response && typeof response === 'object' && 'data' in response) {
      return (response as ApiWrapper<T>).data;
    }
    return response as T | null | undefined;
  }

  getMyProfile(): Observable<PatientProfileDTO> {
    return this.http
      .get<ApiWrapper<PatientProfileDTO>>(`${this.base}/profile`)
      .pipe(map((r) => r.data));
  }

  getHealthSummary(): Observable<HealthSummaryDTO> {
    return this.http
      .get<ApiWrapper<HealthSummaryRaw>>(`${this.base}/health-summary`)
      .pipe(map((r) => mapHealthSummary(r.data)));
  }

  getMyAppointments(): Observable<PortalAppointment[]> {
    return this.http.get<ApiWrapper<AppointmentApiResponse[]>>(`${this.base}/appointments`).pipe(
      map((r) => (r.data ?? []).map(mapAppointment)),
      catchError(() => of([])),
    );
  }

  getMyMedications(): Observable<MedicationSummary[]> {
    return this.http.get<ApiWrapper<MedicationSummary[]>>(`${this.base}/medications`).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }

  getMyPrescriptions(): Observable<PortalPrescription[]> {
    return this.http.get<ApiWrapper<PrescriptionApiResponse[]>>(`${this.base}/prescriptions`).pipe(
      map((r) =>
        (r.data ?? []).map((p) => ({
          id: p.id,
          medicationName: p.medicationName ?? p.medicationDisplayName ?? '',
          dosage: p.dosage ?? '',
          frequency: p.frequency ?? '',
          duration: p.duration ?? '',
          notes: p.notes ?? '',
          prescribedBy: p.staffFullName ?? '',
          prescribedDate: p.createdAt ?? '',
          status: p.status ?? '',
        })),
      ),
      catchError(() => of([])),
    );
  }

  getMyLabResults(limit = 20): Observable<LabResultSummary[]> {
    return this.http
      .get<ApiWrapper<LabResultApiResponse[]>>(`${this.base}/lab-results`, { params: { limit } })
      .pipe(
        map((r) => (r.data ?? []).map(mapLabResult)),
        catchError(() => of([])),
      );
  }

  getMyVitals(limit = 10): Observable<VitalSignSummary[]> {
    return this.http
      .get<ApiWrapper<PatientVitalSignRaw[]>>(`${this.base}/vitals`, { params: { limit } })
      .pipe(
        map((r) => flattenVitals(r.data ?? [])),
        catchError(() => of([])),
      );
  }

  getMyEncounters(): Observable<PortalEncounter[]> {
    return this.http.get<ApiWrapper<EncounterApiResponse[]>>(`${this.base}/encounters`).pipe(
      map((r) =>
        (r.data ?? []).map((e) => ({
          id: e.id,
          date: e.encounterDate,
          type: e.encounterType ?? '',
          providerName: e.staffName ?? e.staffFullName ?? '',
          department: e.departmentName ?? '',
          chiefComplaint: e.note?.chiefComplaint ?? '',
          diagnosisSummary: e.note?.assessment ?? '',
          status: e.status ?? '',
          notes: e.notes ?? '',
          hospitalName: e.hospitalName ?? '',
          appointmentReason: e.appointmentReason ?? '',
        })),
      ),
      catchError(() => of([])),
    );
  }

  getMyInvoices(): Observable<PortalInvoice[]> {
    return this.http
      .get<ApiWrapper<{ content: PortalInvoice[] }>>(`${this.base}/billing/invoices`)
      .pipe(
        map((r) => r.data?.content ?? []),
        catchError(() => of([])),
      );
  }

  payInvoice(invoiceId: string, payment: PatientPaymentRequest): Observable<PortalInvoice> {
    return this.http
      .post<ApiWrapper<PortalInvoice>>(`${this.base}/billing/invoices/${invoiceId}/pay`, payment)
      .pipe(map((r) => r.data));
  }

  getMyCareTeam(): Observable<CareTeamDTO> {
    return this.http.get<ApiWrapper<CareTeamDTO>>(`${this.base}/care-team`).pipe(
      map((r) => r.data),
      catchError(() => of({ members: [] })),
    );
  }

  getMyImmunizations(): Observable<ImmunizationSummary[]> {
    return this.http.get<ApiWrapper<ImmunizationApiResponse[]>>(`${this.base}/immunizations`).pipe(
      map((r) =>
        (r.data ?? []).map((im) => ({
          id: im.id,
          vaccineName: im.vaccineDisplay ?? im.vaccineType ?? '',
          dateAdministered: im.administrationDate ?? '',
          provider: im.administeredByName ?? '',
          status: im.status ?? '',
          site: im.site ?? '',
          route: im.route ?? '',
          lotNumber: im.lotNumber ?? '',
          manufacturer: im.manufacturer ?? '',
          doseNumber: im.doseNumber ?? null,
          totalDosesInSeries: im.totalDosesInSeries ?? null,
        })),
      ),
      catchError(() => of([])),
    );
  }

  // ── After-Visit Summaries ──────────────────────────────────────────

  getAfterVisitSummaries(): Observable<AfterVisitSummary[]> {
    return this.http
      .get<ApiMaybeWrapped<DischargeSummaryApiResponse[]>>(`${this.base}/after-visit-summaries`)
      .pipe(
        map((response) => {
          const summaries = this.unwrapData(response) ?? [];
          return summaries.map((d) => ({
            id: d.id,
            encounterDate: d.dischargeDate ?? '',
            encounterType: d.encounterType ?? '',
            providerName: d.dischargingProviderName ?? '',
            hospitalName: d.hospitalName ?? '',
            diagnoses: d.dischargeDiagnosis
              ? d.dischargeDiagnosis
                  .split(';')
                  .map((s: string) => s.trim())
                  .filter(Boolean)
              : [],
            treatmentSummary: d.hospitalCourse ?? '',
            disposition: d.disposition ?? '',
            dischargeCondition: d.dischargeCondition ?? '',
            followUpInstructions: d.followUpInstructions ?? '',
            activityRestrictions: d.activityRestrictions ?? '',
            dietInstructions: d.dietInstructions ?? '',
            woundCareInstructions: d.woundCareInstructions ?? '',
            warningSigns: d.warningSigns ?? '',
            patientEducation: d.patientEducationProvided ?? '',
            additionalNotes: d.additionalNotes ?? '',
            followUpDate: d.followUpAppointments?.[0]?.scheduledDate ?? null,
            medications: (d.medicationReconciliation ?? []).map((m) => ({
              name: m.medicationName ?? '',
              dosage: m.dosage ?? '',
              frequency: m.frequency ?? '',
            })),
            status: d.isFinalized ? 'FINALIZED' : 'DRAFT',
          }));
        }),
        catchError(() => of([])),
      );
  }

  // ── Consent Management ─────────────────────────────────────────────

  getMyConsents(): Observable<PatientConsent[]> {
    return this.http
      .get<ApiWrapper<PageWrapper<ConsentApiResponse>>>(`${this.base}/consents`, {
        params: { page: 0, size: 50 },
      })
      .pipe(
        map((r) => (r.data?.content ?? []).map((c) => this.mapConsent(c))),
        catchError(() => of([])),
      );
  }

  grantConsent(dto: PortalConsentRequest): Observable<PatientConsent> {
    return this.http
      .post<ApiWrapper<ConsentApiResponse>>(`${this.base}/consents`, dto)
      .pipe(map((r) => this.mapConsent(r.data)));
  }

  private mapConsent(c: ConsentApiResponse): PatientConsent {
    return {
      id: c.id,
      fromHospitalId: c.fromHospital?.id ?? '',
      fromHospitalName: c.fromHospital?.name ?? '',
      toHospitalId: c.toHospital?.id ?? '',
      toHospitalName: c.toHospital?.name ?? '',
      purpose: c.purpose ?? '',
      grantedAt: c.consentTimestamp ?? '',
      expiresAt: c.consentExpiration ?? '',
      status: c.consentGiven ? 'ACTIVE' : 'REVOKED',
    };
  }

  revokeConsent(fromHospitalId: string, toHospitalId: string): Observable<void> {
    return this.http
      .delete<ApiWrapper<void>>(`${this.base}/consents`, {
        params: { fromHospitalId, toHospitalId },
      })
      .pipe(map(() => void 0));
  }

  // ── Access Log ─────────────────────────────────────────────────────

  getMyAccessLog(): Observable<AccessLogEntry[]> {
    return this.http
      .get<ApiWrapper<PageWrapper<AccessLogEntry>>>(`${this.base}/access-log`, {
        params: { page: 0, size: 50 },
      })
      .pipe(
        map((r) => r.data?.content ?? []),
        catchError(() => of([])),
      );
  }

  // ── Appointment Actions ────────────────────────────────────────────

  cancelAppointment(dto: CancelAppointmentRequest): Observable<unknown> {
    return this.http
      .put<ApiWrapper<unknown>>(`${this.base}/appointments/cancel`, dto)
      .pipe(map((r) => r.data));
  }

  rescheduleAppointment(dto: RescheduleAppointmentRequest): Observable<unknown> {
    return this.http
      .put<ApiWrapper<unknown>>(`${this.base}/appointments/reschedule`, dto)
      .pipe(map((r) => r.data));
  }

  // ── Self-Scheduling ────────────────────────────────────────────────

  bookAppointment(dto: BookAppointmentRequest): Observable<PortalAppointment> {
    return this.http
      .post<ApiWrapper<AppointmentApiResponse>>(`${this.base}/appointments`, dto)
      .pipe(map((r) => mapAppointment(r.data)));
  }

  getSchedulingHospitals(): Observable<SchedulingHospital[]> {
    return this.http.get<ApiWrapper<SchedulingHospital[]>>(`${this.base}/booking/hospitals`).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }

  getSchedulingDepartments(hospitalId: string): Observable<SchedulingDepartment[]> {
    return this.http
      .get<ApiWrapper<SchedulingDepartment[]>>(
        `${this.base}/booking/hospitals/${hospitalId}/departments`,
      )
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }

  getSchedulingProviders(
    hospitalId: string,
    departmentId: string,
  ): Observable<SchedulingProvider[]> {
    return this.http
      .get<ApiWrapper<SchedulingProvider[]>>(
        `${this.base}/booking/hospitals/${hospitalId}/departments/${departmentId}/providers`,
      )
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }

  // ── Home Vitals ────────────────────────────────────────────────────

  recordHomeVital(dto: HomeVitalReading): Observable<VitalSignSummary> {
    return this.http
      .post<ApiWrapper<VitalSignSummary>>(`${this.base}/vitals`, dto)
      .pipe(map((r) => r.data));
  }

  // ── Medication Refills ─────────────────────────────────────────────

  requestRefill(dto: RefillRequest): Observable<MedicationRefill> {
    return this.http
      .post<ApiWrapper<MedicationRefill>>(`${this.base}/refills`, dto)
      .pipe(map((r) => r.data));
  }

  getMyRefills(): Observable<MedicationRefill[]> {
    return this.http
      .get<ApiWrapper<PageWrapper<MedicationRefill>>>(`${this.base}/refills`, {
        params: { page: 0, size: 50 },
      })
      .pipe(
        map((r) => r.data?.content ?? []),
        catchError(() => of([])),
      );
  }

  cancelRefill(refillId: string): Observable<MedicationRefill> {
    return this.http
      .put<ApiWrapper<MedicationRefill>>(`${this.base}/refills/${refillId}/cancel`, {})
      .pipe(map((r) => r.data));
  }

  // ── Proxy / Family Access ─────────────────────────────────────────

  getMyProxies(): Observable<ProxyResponse[]> {
    return this.http.get<ApiWrapper<ProxyResponse[]>>(`${this.base}/proxies`).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }

  grantProxy(dto: ProxyGrantRequest): Observable<ProxyResponse> {
    return this.http
      .post<ApiWrapper<ProxyResponse>>(`${this.base}/proxies`, dto)
      .pipe(map((r) => r.data));
  }

  revokeProxy(proxyId: string): Observable<void> {
    return this.http
      .delete<ApiWrapper<void>>(`${this.base}/proxies/${proxyId}`)
      .pipe(map(() => void 0));
  }

  getMyProxyAccess(): Observable<ProxyResponse[]> {
    return this.http.get<ApiWrapper<ProxyResponse[]>>(`${this.base}/proxy-access`).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }

  // ── Patient education (self-service) ───────────────────────────────
  // Backed by /me/patient/education — the staff /patient-education routes
  // exclude ROLE_PATIENT. Only material actually assigned to this patient is
  // readable; anything else 404s.

  getMyEducation(): Observable<PatientEducationItem[]> {
    return this.http.get<ApiWrapper<PatientEducationItem[]>>(`${this.base}/education`).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }

  getMyEducationItem(resourceId: string): Observable<PatientEducationItem> {
    return this.http
      .get<ApiWrapper<PatientEducationItem>>(`${this.base}/education/${resourceId}`)
      .pipe(map((r) => r.data));
  }

  updateMyEducationProgress(
    resourceId: string,
    update: PatientEducationProgressUpdate,
  ): Observable<PatientEducationItem> {
    return this.http
      .put<ApiWrapper<PatientEducationItem>>(
        `${this.base}/education/${resourceId}/progress`,
        update,
      )
      .pipe(map((r) => r.data));
  }

  getMyEducationQuestions(): Observable<PatientEducationQuestion[]> {
    return this.http
      .get<ApiWrapper<PatientEducationQuestion[]>>(`${this.base}/education/questions`)
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }

  submitMyEducationQuestion(
    dto: PatientEducationQuestionSubmit,
  ): Observable<PatientEducationQuestion> {
    return this.http
      .post<ApiWrapper<PatientEducationQuestion>>(`${this.base}/education/questions`, dto)
      .pipe(map((r) => r.data));
  }

  // ── Proxy data viewing ─────────────────────────────────────────────
  // Errors deliberately propagate (no catchError) so the viewer can render
  // an explicit error state — a 403 (missing scope) must not look like
  // "this patient has no data".

  getProxyAppointments(patientId: string): Observable<PortalAppointment[]> {
    return this.http
      .get<ApiWrapper<AppointmentApiResponse[]>>(
        `${this.base}/proxy-access/${patientId}/appointments`,
      )
      .pipe(map((r) => (r.data ?? []).map(mapAppointment)));
  }

  getProxyMedications(patientId: string, limit = 50): Observable<MedicationSummary[]> {
    return this.http
      .get<ApiWrapper<MedicationSummary[]>>(`${this.base}/proxy-access/${patientId}/medications`, {
        params: { limit },
      })
      .pipe(map((r) => r.data ?? []));
  }

  getProxyLabResults(patientId: string, limit = 50): Observable<LabResultSummary[]> {
    return this.http
      .get<ApiWrapper<LabResultApiResponse[]>>(
        `${this.base}/proxy-access/${patientId}/lab-results`,
        {
          params: { limit },
        },
      )
      .pipe(map((r) => (r.data ?? []).map(mapLabResult)));
  }

  getProxyBilling(patientId: string): Observable<PortalInvoice[]> {
    return this.http
      .get<ApiWrapper<{ content: PortalInvoice[] }>>(
        `${this.base}/proxy-access/${patientId}/billing`,
        {
          params: { page: 0, size: 20 },
        },
      )
      .pipe(map((r) => r.data?.content ?? []));
  }

  getProxyRecords(patientId: string): Observable<HealthSummaryDTO> {
    return this.http
      .get<ApiWrapper<HealthSummaryRaw>>(`${this.base}/proxy-access/${patientId}/records`)
      .pipe(map((r) => mapHealthSummary(r.data)));
  }

  // ── Documents (Phase 3) ────────────────────────────────────────────────

  uploadDocument(
    file: File,
    documentType: PatientDocumentType,
    collectionDate?: string,
    notes?: string,
  ): Observable<PatientDocumentResponse> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('documentType', documentType);
    if (collectionDate) formData.append('collectionDate', collectionDate);
    if (notes) formData.append('notes', notes);
    return this.http
      .post<ApiWrapper<PatientDocumentResponse>>(`${this.base}/documents`, formData)
      .pipe(map((r) => r.data));
  }

  listDocuments(
    documentType?: PatientDocumentType,
    page = 0,
    size = 20,
  ): Observable<{ content: PatientDocumentResponse[]; totalElements: number }> {
    let url = `${this.base}/documents?page=${page}&size=${size}&sort=createdAt,desc`;
    if (documentType) url += `&documentType=${documentType}`;
    return this.http.get<ApiWrapper<PageWrapper<PatientDocumentResponse>>>(url).pipe(
      map((r) => ({ content: r.data?.content ?? [], totalElements: r.data?.totalElements ?? 0 })),
      catchError(() => of({ content: [], totalElements: 0 })),
    );
  }

  getDocument(documentId: string): Observable<PatientDocumentResponse> {
    return this.http
      .get<ApiWrapper<PatientDocumentResponse>>(`${this.base}/documents/${documentId}`)
      .pipe(
        map((r) => r.data),
        catchError(() => of(null as unknown as PatientDocumentResponse)),
      );
  }

  deleteDocument(documentId: string): Observable<void> {
    return this.http
      .delete<ApiWrapper<void>>(`${this.base}/documents/${documentId}`)
      .pipe(map(() => void 0));
  }

  /**
   * Document bytes stream only through this authenticated,
   * ownership-checked endpoint — the public /uploads mapping is gone,
   * and a bare href carries no bearer token.
   */
  downloadDocumentBlob(documentId: string): Observable<Blob> {
    return this.http.get(`${this.base}/documents/${documentId}/download`, {
      responseType: 'blob',
    });
  }

  // ── Notifications (Phase 3) ───────────────────────────────────────────

  getMyNotifications(
    read?: boolean,
    page = 0,
    size = 20,
  ): Observable<{ content: PortalNotification[]; totalElements: number }> {
    let url = `${this.base}/notifications?page=${page}&size=${size}`;
    if (read !== undefined) url += `&read=${read}`;
    return this.http
      .get<ApiWrapper<PageWrapper<PortalNotification>>>(url)
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], totalElements: r.data?.totalElements ?? 0 })),
      );
  }

  getUnreadNotificationCount(): Observable<number> {
    return this.http
      .get<ApiWrapper<{ unreadCount: number }>>(`${this.base}/notifications/unread-count`)
      .pipe(
        map((r) => r.data?.unreadCount ?? 0),
        catchError(() => of(0)),
      );
  }

  markNotificationRead(notificationId: string): Observable<void> {
    return this.http
      .put<ApiWrapper<void>>(`${this.base}/notifications/${notificationId}/read`, {})
      .pipe(map(() => void 0));
  }

  markAllNotificationsRead(): Observable<number> {
    return this.http
      .put<ApiWrapper<{ updated: number }>>(`${this.base}/notifications/read-all`, {})
      .pipe(
        map((r) => r.data?.updated ?? 0),
        catchError(() => of(0)),
      );
  }

  getNotificationPreferences(): Observable<NotificationPreference[]> {
    return this.http
      .get<ApiWrapper<NotificationPreference[]>>(`${this.base}/notification-preferences`)
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }

  updateNotificationPreferences(
    updates: NotificationPreferenceUpdate[],
  ): Observable<NotificationPreference[]> {
    return this.http
      .put<ApiWrapper<NotificationPreference[]>>(`${this.base}/notification-preferences`, updates)
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }

  // ── MVP 4: Pre-Visit Questionnaires & Pre-Check-In ────────────────

  getQuestionnairesForAppointment(appointmentId: string): Observable<QuestionnaireDTO[]> {
    return this.http
      .get<ApiWrapper<QuestionnaireDTO[]>>(
        `${this.base}/appointments/${appointmentId}/questionnaires`,
      )
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }

  submitPreCheckIn(appointmentId: string, dto: PreCheckInRequest): Observable<PreCheckInResponse> {
    return this.http
      .post<ApiWrapper<PreCheckInResponse>>(
        `${this.base}/appointments/${appointmentId}/pre-checkin`,
        dto,
      )
      .pipe(map((r) => r.data));
  }

  // ── Medical & Family History ───────────────────────────────────────

  getMyMedicalHistory(): Observable<PatientDiagnosisSummary[]> {
    return this.http
      .get<ApiWrapper<PatientDiagnosisSummary[]>>(`${this.base}/medical-history`)
      .pipe(map((r) => r.data ?? []));
  }

  getMySurgicalHistory(): Observable<SurgicalHistorySummary[]> {
    return this.http
      .get<ApiWrapper<SurgicalHistorySummary[]>>(`${this.base}/surgical-history`)
      .pipe(map((r) => r.data ?? []));
  }

  getMyFamilyHistory(): Observable<FamilyHistorySummary[]> {
    return this.http
      .get<ApiWrapper<FamilyHistorySummary[]>>(`${this.base}/family-history`)
      .pipe(map((r) => r.data ?? []));
  }

  getMySocialHistory(): Observable<SocialHistorySummary | null> {
    return this.http
      .get<ApiWrapper<SocialHistorySummary | null>>(`${this.base}/social-history`)
      .pipe(map((r) => r.data ?? null));
  }
}
