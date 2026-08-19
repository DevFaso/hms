import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Wire shapes for {@code GET /api/patients/:patientId/storyboard}.
 * The Storyboard banner aggregates allergies, problems, the active
 * encounter, and code status in a single payload so the chart can
 * render without N round-trips on flaky 3G/4G connections.
 */
export interface StoryboardPatientHeader {
  id: string;
  mrn?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  fullName?: string | null;
  dateOfBirth?: string | null;
  ageYears?: number | null;
  gender?: string | null;
  bloodType?: string | null;
}

export type AllergySeverity = 'MILD' | 'MODERATE' | 'SEVERE' | 'LIFE_THREATENING' | 'UNKNOWN';

export interface StoryboardAllergy {
  id: string;
  allergenDisplay: string;
  allergenCode?: string | null;
  severity?: AllergySeverity | null;
  verificationStatus?: string | null;
  reaction?: string | null;
}

export interface StoryboardProblem {
  id: string;
  problemDisplay: string;
  problemCode?: string | null;
  icdVersion?: string | null;
  status?: string | null;
  severity?: string | null;
  chronic: boolean;
  onsetDate?: string | null;
}

export interface StoryboardActiveEncounter {
  id: string;
  code?: string | null;
  encounterType?: string | null;
  status?: string | null;
  encounterDate?: string | null;
  departmentName?: string | null;
  staffFullName?: string | null;
  roomAssignment?: string | null;
  chiefComplaint?: string | null;
}

export interface StoryboardDirective {
  id: string;
  directiveType?: string | null;
  status?: string | null;
  effectiveDate?: string | null;
  expirationDate?: string | null;
  description?: string | null;
}

export interface StoryboardCodeStatus {
  status?: string | null;
  directives?: StoryboardDirective[] | null;
}

export interface PatientStoryboard {
  patient: StoryboardPatientHeader;
  allergies: StoryboardAllergy[];
  problems: StoryboardProblem[];
  activeEncounter?: StoryboardActiveEncounter | null;
  codeStatus?: StoryboardCodeStatus | null;
  hasHighSeverityAllergy: boolean;
  hasChronicProblem: boolean;
  hospitalId?: string | null;
  hospitalName?: string | null;
  generatedAt?: string | null;
}

/**
 * Calls {@code GET /api/patients/:patientId/storyboard}. The auth
 * interceptor prepends {@code /api} to relative URLs.
 */
@Injectable({ providedIn: 'root' })
export class StoryboardService {
  private readonly http = inject(HttpClient);

  getStoryboard(patientId: string, hospitalId?: string | null): Observable<PatientStoryboard> {
    let params = new HttpParams();
    if (hospitalId) {
      params = params.set('hospitalId', hospitalId);
    }
    return this.http.get<PatientStoryboard>(`/patients/${patientId}/storyboard`, { params });
  }
}
