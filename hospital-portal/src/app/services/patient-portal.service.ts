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
}

export interface MedicationSummary {
  id: string;
  name: string;
  dosage: string;
  frequency: string;
  prescribedBy: string;
  startDate: string;
  status: string;
}

export interface VitalSignSummary {
  id: string;
  type: string;
  value: string;
  unit: string;
  recordedAt: string;
  source: string;
}

export interface ImmunizationSummary {
  id: string;
  vaccineName: string;
  dateAdministered: string;
  provider: string;
  status: string;
}

export interface PortalAppointment {
  id: string;
  date: string;
  startTime: string;
  endTime: string;
  providerName: string;
  department: string;
  reason: string;
  status: string;
  location: string;
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
}

/** Raw shape returned by the backend EncounterResponseDTO */
interface EncounterApiResponse {
  id: string;
  encounterDate: string;
  encounterType: string;
  staffName: string;
  staffFullName: string;
  departmentName: string;
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
  prescribedBy: string;
  prescribedDate: string;
  refillsRemaining: number;
  status: string;
}

export interface AfterVisitSummary {
  id: string;
  encounterDate: string;
  providerName: string;
  department: string;
  chiefComplaint: string;
  diagnoses: string[];
  treatmentSummary: string;
  instructions: string;
  followUpDate: string | null;
  medications: string[];
  status: string;
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

interface ApiWrapper<T> {
  data: T;
  success: boolean;
}

interface PageWrapper<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class PatientPortalService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/me/patient';

  getMyProfile(): Observable<PatientProfileDTO> {
    return this.http
      .get<ApiWrapper<PatientProfileDTO>>(`${this.base}/profile`)
      .pipe(map((r) => r.data));
  }

  getHealthSummary(): Observable<HealthSummaryDTO> {
    return this.http
      .get<ApiWrapper<HealthSummaryDTO>>(`${this.base}/health-summary`)
      .pipe(map((r) => r.data));
  }

  getMyAppointments(): Observable<PortalAppointment[]> {
    return this.http.get<ApiWrapper<PortalAppointment[]>>(`${this.base}/appointments`).pipe(
      map((r) => r.data ?? []),
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
    return this.http.get<ApiWrapper<PortalPrescription[]>>(`${this.base}/prescriptions`).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }

  getMyLabResults(limit = 20): Observable<LabResultSummary[]> {
    return this.http
      .get<ApiWrapper<LabResultSummary[]>>(`${this.base}/lab-results`, { params: { limit } })
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }

  getMyVitals(limit = 10): Observable<VitalSignSummary[]> {
    return this.http
      .get<ApiWrapper<VitalSignSummary[]>>(`${this.base}/vitals`, { params: { limit } })
      .pipe(
        map((r) => r.data ?? []),
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
    return this.http.get<ApiWrapper<ImmunizationSummary[]>>(`${this.base}/immunizations`).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }

  // ── After-Visit Summaries ──────────────────────────────────────────

  getAfterVisitSummaries(): Observable<AfterVisitSummary[]> {
    return this.http
      .get<ApiWrapper<AfterVisitSummary[]>>(`${this.base}/after-visit-summaries`)
      .pipe(
        map((r) => r.data ?? []),
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
      .pipe(
        map((r) => r.data),
        catchError(() => of(null as unknown as PatientDocumentResponse)),
      );
  }

  listDocuments(
    documentType?: PatientDocumentType,
    page = 0,
    size = 20,
  ): Observable<{ content: PatientDocumentResponse[]; totalElements: number }> {
    let url = `${this.base}/documents?page=${page}&size=${size}&sort=createdAt,desc`;
    if (documentType) url += `&documentType=${documentType}`;
    return this.http
      .get<ApiWrapper<PageWrapper<PatientDocumentResponse>>>(url)
      .pipe(
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
        catchError(() => of({ content: [], totalElements: 0 })),
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

  updateNotificationPreferences(updates: NotificationPreferenceUpdate[]): Observable<NotificationPreference[]> {
    return this.http
      .put<ApiWrapper<NotificationPreference[]>>(`${this.base}/notification-preferences`, updates)
      .pipe(
        map((r) => r.data ?? []),
        catchError(() => of([])),
      );
  }
}
