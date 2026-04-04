import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export interface LabOrderResponse {
  id: string;
  labOrderCode: string;
  patientFullName: string;
  patientEmail: string;
  hospitalName: string;
  labTestName: string;
  labTestCode: string;
  orderDatetime: string;
  status: string;
  clinicalIndication: string;
  medicalNecessityNote: string;
  notes: string;
  primaryDiagnosisCode: string;
  additionalDiagnosisCodes: string[];
  orderChannel: string;
  createdAt: string;
  updatedAt: string;
}

export interface LabResultResponse {
  id: string;
  labOrderId: string;
  resultValue: string;
  resultUnit: string;
  referenceRange: string;
  abnormalFlag: string;
  status: string;
  performedAt: string;
  verifiedAt: string;
  notes: string;
}

export interface LabTestDefinition {
  id: string;
  testCode: string;
  testName: string;
  description: string;
  category: string;
  sampleType: string;
  isActive: boolean;
  approvalStatus:
    | 'DRAFT'
    | 'PENDING_QA_REVIEW'
    | 'PENDING_DIRECTOR_APPROVAL'
    | 'APPROVED'
    | 'ACTIVE'
    | 'REJECTED'
    | 'RETIRED';
  approvedById: string | null;
  approvedAt: string | null;
  reviewedById: string | null;
  reviewedAt: string | null;
  rejectionReason: string | null;
}

export interface LabTestDefinitionApprovalRequest {
  action: 'SUBMIT_FOR_QA' | 'COMPLETE_QA_REVIEW' | 'APPROVE' | 'ACTIVATE' | 'REJECT' | 'RETIRE';
  rejectionReason?: string;
}

export type ValidationStudyType =
  | 'PRECISION'
  | 'ACCURACY'
  | 'REFERENCE_RANGE'
  | 'METHOD_COMPARISON'
  | 'INTERFERENCE'
  | 'CARRYOVER'
  | 'LINEARITY';

export interface LabTestValidationStudy {
  id: string;
  labTestDefinitionId: string;
  testCode: string;
  testName: string;
  studyType: ValidationStudyType;
  studyDate: string;
  performedByUserId: string | null;
  performedByDisplay: string | null;
  summary: string | null;
  resultData: string | null;
  passed: boolean;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LabTestValidationStudyRequest {
  studyType: ValidationStudyType;
  studyDate: string;
  performedByUserId?: string;
  performedByDisplay?: string;
  summary?: string;
  resultData?: string;
  passed: boolean;
  notes?: string;
}

export type QcEventLevel = 'LOW_CONTROL' | 'HIGH_CONTROL';

export interface LabQcEvent {
  id: string;
  hospitalId: string;
  analyzerId: string | null;
  testDefinitionId: string;
  testDefinitionName: string;
  qcLevel: QcEventLevel;
  measuredValue: number;
  expectedValue: number;
  passed: boolean;
  recordedAt: string;
  recordedById: string | null;
  notes: string | null;
  createdAt: string;
}

export interface LabQcSummary {
  testDefinitionId: string;
  testName: string;
  totalEvents: number;
  passedEvents: number;
  failedEvents: number;
  passRate: number;
  lastEventDate: string | null;
}

export interface LabValidationSummary {
  testDefinitionId: string;
  testName: string;
  testCode: string;
  totalStudies: number;
  passedStudies: number;
  failedStudies: number;
  passRate: number;
  lastStudyDate: string | null;
}

export interface LabOrderRequest {
  patientId: string;
  hospitalId: string;
  encounterId?: string;
  testName: string;
  testCode?: string;
  status: string;
  priority?: string;
  clinicalIndication: string;
  medicalNecessityNote: string;
  notes?: string;
  orderingStaffId: string;
  labTestDefinitionId: string;
  assignmentId: string;
  primaryDiagnosisCode: string;
  orderChannel: string;
  providerSignature: string;
  documentationSharedWithLab: boolean;
}

interface ApiWrapper<T> {
  data: T;
  success: boolean;
}

interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class LabService {
  private readonly http = inject(HttpClient);

  listOrders(params?: {
    patientId?: string;
    page?: number;
    size?: number;
  }): Observable<LabOrderResponse[]> {
    let httpParams = new HttpParams();
    if (params?.patientId) httpParams = httpParams.set('patientId', params.patientId);
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http
      .get<ApiWrapper<PageResponse<LabOrderResponse>>>('/lab-orders', { params: httpParams })
      .pipe(map((res) => res?.data?.content ?? []));
  }

  getOrder(id: string): Observable<LabOrderResponse> {
    return this.http
      .get<ApiWrapper<LabOrderResponse>>(`/lab-orders/${id}`)
      .pipe(map((res) => res.data));
  }

  listResults(params?: { page?: number; size?: number }): Observable<LabResultResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http
      .get<ApiWrapper<PageResponse<LabResultResponse>>>('/lab-results', { params: httpParams })
      .pipe(map((res) => res?.data?.content ?? []));
  }

  getResult(id: string): Observable<LabResultResponse> {
    return this.http
      .get<ApiWrapper<LabResultResponse>>(`/lab-results/${id}`)
      .pipe(map((res) => res.data));
  }

  getPendingReviewResults(): Observable<LabResultResponse[]> {
    return this.http
      .get<ApiWrapper<LabResultResponse[]>>('/lab-results/pending-review')
      .pipe(map((res) => res?.data ?? []));
  }

  createOrder(req: LabOrderRequest): Observable<LabOrderResponse> {
    return this.http
      .post<ApiWrapper<LabOrderResponse>>('/lab-orders', req)
      .pipe(map((res) => res.data));
  }

  listTestDefinitions(): Observable<LabTestDefinition[]> {
    return this.http
      .get<ApiWrapper<LabTestDefinition[]>>('/lab-test-definitions')
      .pipe(map((res) => res?.data ?? []));
  }

  searchTestDefinitions(params: {
    keyword?: string;
    approvalStatus?: LabTestDefinition['approvalStatus'];
    page?: number;
    size?: number;
  }): Observable<{ content: LabTestDefinition[]; totalElements: number }> {
    let httpParams = new HttpParams();
    if (params.keyword) httpParams = httpParams.set('keyword', params.keyword);
    if (params.approvalStatus) httpParams = httpParams.set('approvalStatus', params.approvalStatus);
    if (params.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http
      .get<ApiWrapper<PageResponse<LabTestDefinition>>>('/lab-test-definitions/search', {
        params: httpParams,
      })
      .pipe(
        map((res) => ({
          content: res?.data?.content ?? [],
          totalElements: res?.data?.totalElements ?? 0,
        })),
      );
  }

  updateOrder(id: string, req: Partial<LabOrderRequest>): Observable<LabOrderResponse> {
    return this.http
      .put<ApiWrapper<LabOrderResponse>>(`/lab-orders/${id}`, req)
      .pipe(map((res) => res.data));
  }

  deleteOrder(id: string): Observable<void> {
    return this.http.delete<void>(`/lab-orders/${id}`);
  }

  submitApprovalAction(
    id: string,
    req: LabTestDefinitionApprovalRequest,
  ): Observable<LabTestDefinition> {
    return this.http
      .post<ApiWrapper<LabTestDefinition>>(`/lab-test-definitions/${id}/approval`, req)
      .pipe(map((res) => res.data));
  }

  getValidationStudies(definitionId: string): Observable<LabTestValidationStudy[]> {
    return this.http
      .get<
        ApiWrapper<LabTestValidationStudy[]>
      >(`/lab-test-definitions/${definitionId}/validation-studies`)
      .pipe(map((res) => res?.data ?? []));
  }

  createValidationStudy(
    definitionId: string,
    req: LabTestValidationStudyRequest,
  ): Observable<LabTestValidationStudy> {
    return this.http
      .post<
        ApiWrapper<LabTestValidationStudy>
      >(`/lab-test-definitions/${definitionId}/validation-studies`, req)
      .pipe(map((res) => res.data));
  }

  updateValidationStudy(
    id: string,
    req: LabTestValidationStudyRequest,
  ): Observable<LabTestValidationStudy> {
    return this.http
      .put<ApiWrapper<LabTestValidationStudy>>(`/lab-test-validation-studies/${id}`, req)
      .pipe(map((res) => res.data));
  }

  deleteValidationStudy(id: string): Observable<void> {
    return this.http.delete<void>(`/lab-test-validation-studies/${id}`);
  }

  getQcEventsByDefinition(definitionId: string, size = 150): Observable<LabQcEvent[]> {
    const params = new HttpParams()
      .set('testDefinitionId', definitionId)
      .set('size', String(size))
      .set('sort', 'recordedAt,asc');
    return this.http
      .get<ApiWrapper<PageResponse<LabQcEvent>>>('/lab-qc-events', { params })
      .pipe(map((res) => res?.data?.content ?? []));
  }

  getQcSummary(): Observable<LabQcSummary[]> {
    return this.http
      .get<ApiWrapper<LabQcSummary[]>>('/lab-qc-events/summary')
      .pipe(map((res) => res?.data ?? []));
  }

  getValidationSummary(): Observable<LabValidationSummary[]> {
    return this.http
      .get<ApiWrapper<LabValidationSummary[]>>('/lab-test-validation-studies/summary')
      .pipe(map((res) => res?.data ?? []));
  }

  exportTestDefinitionsCsv(): Observable<Blob> {
    return this.http.get('/lab-test-definitions/export', { responseType: 'blob' });
  }
}
