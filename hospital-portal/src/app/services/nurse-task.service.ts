import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface NurseVitalTask {
  id: string;
  patientId: string;
  patientName: string;
  type: string;
  dueTime: string;
  overdue: boolean;
}

export interface NurseMedicationTask {
  id: string;
  patientId: string;
  patientName: string;
  medication: string;
  dose: string;
  route: string;
  dueTime: string;
  status: string;
}

export interface NurseOrderTask {
  id: string;
  patientId: string;
  patientName: string;
  orderType: string;
  priority: string;
  dueTime: string;
}

export interface NurseHandoff {
  id: string;
  patientId: string;
  patientName: string;
  direction: string;
  updatedAt: string;
  note: string;
}

export interface NurseHandoffCreateRequest {
  patientId: string;
  direction: string;
  note?: string;
  checklistItems?: string[];
}

export interface NurseAnnouncement {
  id: string;
  text: string;
  createdAt: string;
  startsAt: string;
  expiresAt: string;
  category: string;
}

export interface NurseDashboardSummary {
  assignedPatients: number;
  vitalsDue: number;
  medicationsDue: number;
  medicationsOverdue: number;
  ordersPending: number;
  handoffsPending: number;
  announcements: number;
}

// ── MVP 12 interfaces ──────────────────────────────────────────────

export interface NurseWorkboardPatient {
  patientId: string;
  patientName: string;
  mrn: string;
  roomBed: string;
  acuityLevel: string;
  admissionId: string;
  departmentName: string;
  attendingDoctor: string;
  admittedAt: string;
  lastVitalsTime: string | null;
  vitalsDue: boolean;
  medsDue: number;
}

export interface NurseFlowPatientCard {
  patientId: string;
  patientName: string;
  mrn: string;
  admissionId: string;
  acuityLevel: string;
  waitMinutes: number;
  roomBed: string;
  departmentName: string;
  admittedAt: string;
}

export interface NurseFlowBoard {
  pending: NurseFlowPatientCard[];
  active: NurseFlowPatientCard[];
  critical: NurseFlowPatientCard[];
  awaitingDischarge: NurseFlowPatientCard[];
}

export interface NurseAdmissionSummary {
  admissionId: string;
  patientId: string;
  patientName: string;
  mrn: string;
  status: string;
  acuityLevel: string;
  roomBed: string;
  departmentName: string;
  admittingDoctor: string;
  admissionDateTime: string;
  admissionType: string;
}

export interface NurseVitalCaptureRequest {
  temperatureCelsius?: number;
  heartRateBpm?: number;
  respiratoryRateBpm?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  spo2Percent?: number;
  bloodGlucoseMgDl?: number;
  weightKg?: number;
  notes?: string;
}

// ── MVP 13 interfaces ─────────────────────────────────────────────

export interface NurseTaskItem {
  id: string;
  patientId: string;
  patientName: string;
  mrn: string;
  category: string;
  description: string;
  priority: string;
  status: string;
  dueAt: string | null;
  overdue: boolean;
  completedAt: string | null;
  completedByName: string | null;
  completionNote: string | null;
  createdByName: string | null;
}

export interface NurseTaskCreateRequest {
  patientId: string;
  category: string;
  description?: string;
  priority?: string;
  dueAt?: string;
}

export interface NurseInboxItem {
  id: string;
  message: string;
  read: boolean;
  createdAt: string;
}

export interface NurseCareNoteRequest {
  template: 'DAR' | 'SOAPIE';
  // DAR fields
  dataPart?: string;
  actionPart?: string;
  responsePart?: string;
  // SOAPIE fields
  subjective?: string;
  objective?: string;
  assessment?: string;
  plan?: string;
  implementation?: string;
  evaluation?: string;
  // shared
  narrative?: string;
  title?: string;
}

export interface NurseCareNoteResponse {
  noteId: string;
  patientId: string;
  patientName: string;
  template: string;
  title: string;
  summary: string;
  authorName: string;
  documentedAt: string;
}

// ── MVP 14 interfaces ─────────────────────────────────────────────

export interface NursePatient {
  id: string;
  patientId: string;
  firstName: string;
  lastName: string;
  displayName: string;
  mrn: string;
  room: string | null;
  bed: string | null;
  gender: string;
  dateOfBirth: string;
  bloodType: string | null;
  allergies: string | null;
  flags: string[];
  risks: string[];
  chronicConditions: string[];
  hr: number | null;
  bp: string | null;
  spo2: number | null;
  hospitalId: string;
  hospitalName: string;
  departmentName: string | null;
  active: boolean;
}

export interface NursingNoteResponse {
  id: string;
  patientId: string;
  patientName: string;
  patientMrn: string;
  authorName: string;
  authorCredentials: string | null;
  template: string;
  narrative: string | null;
  actionSummary: string | null;
  responseSummary: string | null;
  documentedAt: string;
  createdAt: string;
}

// ── MVP 15 interfaces (Flowsheets + BCMA) ─────────────────────────

export interface FlowsheetEntry {
  id: string;
  patientId: string;
  patientName: string;
  type: string;
  numericValue: number | null;
  unit: string | null;
  textValue: string | null;
  subType: string | null;
  recordedAt: string;
  recordedByName: string;
  notes: string | null;
}

export interface FlowsheetEntryCreateRequest {
  patientId: string;
  type: string;
  numericValue?: number;
  unit?: string;
  textValue?: string;
  subType?: string;
  notes?: string;
}

export interface BcmaCompliance {
  totalAdministrations: number;
  scannedCount: number;
  overrideCount: number;
  complianceRate: number;
  overrideRate: number;
}

@Injectable({ providedIn: 'root' })
export class NurseTaskService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/nurse';

  getVitalsDue(params?: {
    hospitalId?: string;
    window?: string;
    assignee?: string;
  }): Observable<NurseVitalTask[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.window) httpParams = httpParams.set('window', params.window);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    return this.http.get<NurseVitalTask[]>(`${this.baseUrl}/vitals/due`, { params: httpParams });
  }

  getMedicationMAR(params?: {
    hospitalId?: string;
    assignee?: string;
    status?: string;
  }): Observable<NurseMedicationTask[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    if (params?.status) httpParams = httpParams.set('status', params.status);
    return this.http.get<NurseMedicationTask[]>(`${this.baseUrl}/medications/mar`, {
      params: httpParams,
    });
  }

  administerMedication(
    taskId: string,
    data: { status: string; note?: string },
  ): Observable<NurseMedicationTask> {
    return this.http.put<NurseMedicationTask>(
      `${this.baseUrl}/medications/mar/${taskId}/administer`,
      data,
    );
  }

  getOrders(params?: {
    hospitalId?: string;
    assignee?: string;
    status?: string;
    limit?: number;
  }): Observable<NurseOrderTask[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    if (params?.status) httpParams = httpParams.set('status', params.status);
    if (params?.limit != null) httpParams = httpParams.set('limit', params.limit);
    return this.http.get<NurseOrderTask[]>(`${this.baseUrl}/orders`, { params: httpParams });
  }

  getHandoffs(params?: {
    hospitalId?: string;
    assignee?: string;
    limit?: number;
  }): Observable<NurseHandoff[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    if (params?.limit != null) httpParams = httpParams.set('limit', params.limit);
    return this.http.get<NurseHandoff[]>(`${this.baseUrl}/handoffs`, { params: httpParams });
  }

  completeHandoff(handoffId: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/handoffs/${handoffId}/complete`, {});
  }

  updateHandoffTask(handoffId: string, taskId: string, completed: boolean): Observable<unknown> {
    return this.http.patch(`${this.baseUrl}/handoffs/${handoffId}/tasks/${taskId}`, { completed });
  }

  createHandoff(request: NurseHandoffCreateRequest, hospitalId?: string): Observable<NurseHandoff> {
    let httpParams = new HttpParams();
    if (hospitalId) httpParams = httpParams.set('hospitalId', hospitalId);
    return this.http.post<NurseHandoff>(`${this.baseUrl}/handoffs`, request, {
      params: httpParams,
    });
  }

  getAnnouncements(params?: { hospitalId?: string }): Observable<NurseAnnouncement[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    return this.http.get<NurseAnnouncement[]>(`${this.baseUrl}/announcements`, {
      params: httpParams,
    });
  }

  getDashboardSummary(params?: {
    hospitalId?: string;
    assignee?: string;
  }): Observable<NurseDashboardSummary> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    return this.http.get<NurseDashboardSummary>(`${this.baseUrl}/dashboard/summary`, {
      params: httpParams,
    });
  }

  // ── MVP 12 methods ────────────────────────────────────────────────

  getWorkboard(params?: {
    hospitalId?: string;
    assignee?: string;
  }): Observable<NurseWorkboardPatient[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    return this.http.get<NurseWorkboardPatient[]>(`${this.baseUrl}/workboard`, {
      params: httpParams,
    });
  }

  getPatientFlow(params?: {
    hospitalId?: string;
    departmentId?: string;
  }): Observable<NurseFlowBoard> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.departmentId) httpParams = httpParams.set('departmentId', params.departmentId);
    return this.http.get<NurseFlowBoard>(`${this.baseUrl}/patient-flow`, { params: httpParams });
  }

  captureVitals(patientId: string, data: NurseVitalCaptureRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/patients/${patientId}/vitals`, data);
  }

  getPendingAdmissions(params?: {
    hospitalId?: string;
    departmentId?: string;
  }): Observable<NurseAdmissionSummary[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.departmentId) httpParams = httpParams.set('departmentId', params.departmentId);
    return this.http.get<NurseAdmissionSummary[]>(`${this.baseUrl}/admissions/pending`, {
      params: httpParams,
    });
  }

  // ── MVP 13 methods ────────────────────────────────────────────────

  getNursingTasks(params?: { hospitalId?: string; status?: string }): Observable<NurseTaskItem[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.status) httpParams = httpParams.set('status', params.status);
    return this.http.get<NurseTaskItem[]>(`${this.baseUrl}/tasks`, { params: httpParams });
  }

  createNursingTask(data: NurseTaskCreateRequest, hospitalId?: string): Observable<NurseTaskItem> {
    let httpParams = new HttpParams();
    if (hospitalId) httpParams = httpParams.set('hospitalId', hospitalId);
    return this.http.post<NurseTaskItem>(`${this.baseUrl}/tasks`, data, { params: httpParams });
  }

  completeNursingTask(
    taskId: string,
    data?: { completionNote?: string },
    hospitalId?: string,
  ): Observable<NurseTaskItem> {
    let httpParams = new HttpParams();
    if (hospitalId) httpParams = httpParams.set('hospitalId', hospitalId);
    return this.http.put<NurseTaskItem>(`${this.baseUrl}/tasks/${taskId}/complete`, data ?? {}, {
      params: httpParams,
    });
  }

  getNurseInbox(params?: { limit?: number }): Observable<NurseInboxItem[]> {
    let httpParams = new HttpParams();
    if (params?.limit != null) httpParams = httpParams.set('limit', params.limit);
    return this.http.get<NurseInboxItem[]>(`${this.baseUrl}/inbox`, { params: httpParams });
  }

  markInboxRead(itemId: string): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/inbox/${itemId}/read`, {});
  }

  createCareNote(
    patientId: string,
    data: NurseCareNoteRequest,
    hospitalId?: string,
  ): Observable<NurseCareNoteResponse> {
    let httpParams = new HttpParams();
    if (hospitalId) httpParams = httpParams.set('hospitalId', hospitalId);
    return this.http.post<NurseCareNoteResponse>(
      `${this.baseUrl}/patients/${patientId}/care-note`,
      data,
      { params: httpParams },
    );
  }

  // ── MVP 14 methods ────────────────────────────────────────────────

  getPatients(params?: { hospitalId?: string; assignee?: string }): Observable<NursePatient[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    return this.http.get<NursePatient[]>(`${this.baseUrl}/patients`, { params: httpParams });
  }

  getNursingNotes(params: {
    patientId: string;
    hospitalId?: string;
    limit?: number;
  }): Observable<NursingNoteResponse[]> {
    let httpParams = new HttpParams().set('patientId', params.patientId);
    if (params.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params.limit != null) httpParams = httpParams.set('limit', params.limit);
    return this.http.get<NursingNoteResponse[]>(`${this.baseUrl}/notes`, { params: httpParams });
  }

  // ── MVP 15 methods (Flowsheets + BCMA) ────────────────────────────

  getFlowsheetEntries(
    patientId: string,
    params?: { hospitalId?: string; type?: string },
  ): Observable<FlowsheetEntry[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.type) httpParams = httpParams.set('type', params.type);
    return this.http.get<FlowsheetEntry[]>(
      `${this.baseUrl}/patients/${patientId}/flowsheets`,
      { params: httpParams },
    );
  }

  recordFlowsheetEntry(
    data: FlowsheetEntryCreateRequest,
    hospitalId?: string,
  ): Observable<FlowsheetEntry> {
    let httpParams = new HttpParams();
    if (hospitalId) httpParams = httpParams.set('hospitalId', hospitalId);
    return this.http.post<FlowsheetEntry>(
      `${this.baseUrl}/flowsheets`,
      data,
      { params: httpParams },
    );
  }

  reassignTask(taskId: string, targetStaffId: string, hospitalId?: string): Observable<NurseTaskItem> {
    let httpParams = new HttpParams().set('targetStaffId', targetStaffId);
    if (hospitalId) httpParams = httpParams.set('hospitalId', hospitalId);
    return this.http.put<NurseTaskItem>(
      `${this.baseUrl}/tasks/${taskId}/reassign`,
      null,
      { params: httpParams },
    );
  }

  getBcmaCompliance(params?: {
    hospitalId?: string;
    hours?: number;
  }): Observable<BcmaCompliance> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.hours != null) httpParams = httpParams.set('hours', params.hours);
    return this.http.get<BcmaCompliance>(`${this.baseUrl}/bcma/compliance`, {
      params: httpParams,
    });
  }
}
