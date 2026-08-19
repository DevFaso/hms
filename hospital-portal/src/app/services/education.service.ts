import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Patient education — /patient-education (bare DTOs, no wrapper, no paging).
 * Effective backend roles (ROLE_ADMIN is never granted — dead in every list):
 *  - resource create/update: SUPER_ADMIN/DOCTOR/NURSE (no HOSPITAL_ADMIN,
 *    no MIDWIFE — midwives are read-only in this maternal-health module)
 *  - resource delete (soft): SUPER_ADMIN only
 *  - progress create: + RECEPTIONIST (write-only for them); progress reads/
 *    updates: SUPER_ADMIN/DOCTOR/NURSE
 * There is no separate "assignment" concept: the first POST /progress for a
 * (patient, resource) pair with NOT_STARTED is the assign primitive.
 * hospitalId is always taken from the JWT. progressPercentage >= 100
 * auto-completes; a rating recomputes the resource's averageRating.
 */

export type EducationCategory =
  | 'PRENATAL_CARE'
  | 'NUTRITION'
  | 'EXERCISE'
  | 'LABOR_AND_DELIVERY'
  | 'POSTPARTUM_CARE'
  | 'BREASTFEEDING'
  | 'NEWBORN_CARE'
  | 'MENTAL_HEALTH'
  | 'WARNING_SIGNS'
  | 'BIRTH_PLAN'
  | 'PRENATAL_VITAMINS'
  | 'MANAGING_DISCOMFORT'
  | 'HIGH_RISK_PREGNANCY'
  | 'ULTRASOUND_SCANS'
  | 'GENETIC_SCREENING';
export type EducationResourceType =
  | 'ARTICLE'
  | 'VIDEO'
  | 'INTERACTIVE_MODULE'
  | 'INFOGRAPHIC'
  | 'CHECKLIST'
  | 'FAQ'
  | 'PODCAST';
export type EducationComprehensionStatus =
  | 'NOT_STARTED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CONFIRMED_UNDERSTANDING'
  | 'NEEDS_CLARIFICATION'
  | 'FEEDBACK_PROVIDED';

export interface EducationResourceRequest {
  title: string;
  description?: string;
  resourceType: EducationResourceType;
  category: EducationCategory;
  tags?: string[];
  contentUrl?: string;
  textContent?: string;
  thumbnailUrl?: string;
  videoUrl?: string;
  /** Minutes. */
  estimatedDuration?: number;
  isEvidenceBased?: boolean;
  evidenceSource?: string;
  isHighRiskRelevant?: boolean;
  isWarningSignContent?: boolean;
  primaryLanguage?: string;
}

export interface EducationResource extends EducationResourceRequest {
  id: string;
  isActive?: boolean;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
  viewCount?: number;
  completionCount?: number;
  averageRating?: number | null;
  ratingCount?: number;
}

export interface EducationProgressRequest {
  resourceId: string;
  comprehensionStatus?: EducationComprehensionStatus;
  progressPercentage?: number;
  rating?: number;
  feedback?: string;
  needsClarification?: boolean;
  clarificationRequest?: string;
  confirmedUnderstanding?: boolean;
  providerNotes?: string;
}

export interface EducationProgress {
  id: string;
  patientId: string;
  resourceId: string;
  hospitalId?: string;
  comprehensionStatus?: EducationComprehensionStatus;
  progressPercentage?: number | null;
  startedAt?: string;
  completedAt?: string | null;
  lastAccessedAt?: string;
  timeSpentSeconds?: number;
  accessCount?: number;
  rating?: number | null;
  feedback?: string;
  needsClarification?: boolean;
  clarificationRequest?: string;
  confirmedUnderstanding?: boolean;
  providerId?: string;
  providerNotes?: string;
  createdAt?: string;
  updatedAt?: string;
}

@Injectable({ providedIn: 'root' })
export class EducationService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/patient-education';

  /* ── Resources ── */

  listResources(): Observable<EducationResource[]> {
    return this.http.get<EducationResource[]>(`${this.baseUrl}/resources`);
  }

  createResource(req: EducationResourceRequest): Observable<EducationResource> {
    return this.http.post<EducationResource>(`${this.baseUrl}/resources`, req);
  }

  updateResource(id: string, req: EducationResourceRequest): Observable<EducationResource> {
    return this.http.put<EducationResource>(`${this.baseUrl}/resources/${id}`, req);
  }

  /** Soft delete (isActive=false) — SUPER_ADMIN only on the backend. */
  deleteResource(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/resources/${id}`);
  }

  /* ── Progress (assignment = first POST with NOT_STARTED) ── */

  trackProgress(patientId: string, req: EducationProgressRequest): Observable<EducationProgress> {
    const params = new HttpParams().set('patientId', patientId);
    return this.http.post<EducationProgress>(`${this.baseUrl}/progress`, req, { params });
  }

  updateProgress(progressId: string, req: EducationProgressRequest): Observable<EducationProgress> {
    return this.http.put<EducationProgress>(`${this.baseUrl}/progress/${progressId}`, req);
  }

  progressForPatient(patientId: string): Observable<EducationProgress[]> {
    return this.http.get<EducationProgress[]>(`${this.baseUrl}/progress/patient/${patientId}`);
  }
}
