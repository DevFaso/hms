import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Medication history timeline + external pharmacy-fill records —
 * /medication-history (bare DTOs, no wrapper, no pagination).
 * Effective roles (only ROLE_* authorities are ever granted):
 *  - timeline: DOCTOR/NURSE/PHARMACIST/LAB_SCIENTIST (no MIDWIFE, no admins)
 *  - fill create/update: DOCTOR/PHARMACIST; fill reads: + NURSE
 *  - fill delete: admins only (who cannot read) — not exposed in the UI.
 * PharmacyFill records external/retro-documented fills (free-text pharmacy,
 * sourceSystem, externalReferenceId) — distinct from the inventory-decrementing
 * /pharmacy/dispense workflow the portal already covers.
 */

export type InteractionSeverity = 'CONTRAINDICATED' | 'MAJOR' | 'MODERATE' | 'MINOR' | 'UNKNOWN';

/** Wire values are plain strings: 'PRESCRIPTION' | 'PHARMACY_FILL'. */
export type TimelineEntryType = 'PRESCRIPTION' | 'PHARMACY_FILL';

export interface DrugInteraction {
  id?: string;
  drug1Code?: string;
  drug1Name?: string;
  drug2Code?: string;
  drug2Name?: string;
  severity?: InteractionSeverity;
  description?: string;
  recommendation?: string;
  mechanism?: string;
  clinicalEffects?: string;
  requiresAvoidance?: boolean;
  requiresDoseAdjustment?: boolean;
  requiresMonitoring?: boolean;
  monitoringParameters?: string;
  monitoringIntervalHours?: number;
  sourceDatabase?: string;
  evidenceLevel?: string;
}

export interface MedicationTimelineEntry {
  entryId: string;
  entryType: TimelineEntryType;
  medicationName?: string;
  medicationCode?: string;
  strength?: string;
  dosageForm?: string;
  startDate?: string;
  endDate?: string;
  daysSupply?: number;
  duration?: string;
  dosage?: string;
  frequency?: string;
  route?: string;
  quantityDispensed?: number;
  quantityUnit?: string;
  source?: string;
  prescriberName?: string;
  pharmacyName?: string;
  /** PrescriptionStatus name for RX rows; always 'DISPENSED' for fills. */
  status?: string;
  controlledSubstance?: boolean;
  hasOverlap?: boolean;
  overlappingWith?: string[];
  overlapDays?: number;
  hasInteraction?: boolean;
  interactingWith?: string[];
  prescriptionId?: string;
  pharmacyFillId?: string;
  documentedAt?: string;
}

export interface MedicationTimelineResponse {
  timeline: MedicationTimelineEntry[];
  totalMedications: number;
  activeMedications: number;
  controlledSubstances: number;
  medicationsWithOverlaps: number;
  medicationsWithInteractions: number;
  detectedInteractions?: DrugInteraction[];
  polypharmacyDetected: boolean;
  concurrentMedicationsCount?: number;
  warnings?: string[];
}

export interface PharmacyFillBase {
  prescriptionId?: string;
  medicationName?: string;
  ndcCode?: string;
  rxnormCode?: string;
  strength?: string;
  dosageForm?: string;
  fillDate?: string;
  quantityDispensed?: number;
  quantityUnit?: string;
  daysSupply?: number;
  /** 0 = initial fill. */
  refillNumber?: number;
  directions?: string;
  pharmacyName?: string;
  pharmacyNpi?: string;
  pharmacyNcpdp?: string;
  pharmacyLicense?: string;
  facilityCode?: string;
  pharmacyPhone?: string;
  pharmacyAddress?: string;
  prescriberName?: string;
  prescriberNpi?: string;
  prescriberDea?: string;
  sourceSystem?: string;
  externalReferenceId?: string;
  controlledSubstance?: boolean;
  genericSubstitution?: boolean;
  notes?: string;
}

export interface PharmacyFillRequest extends PharmacyFillBase {
  patientId: string;
  hospitalId: string;
  medicationName: string;
  fillDate: string;
}

export interface PharmacyFillResponse extends PharmacyFillBase {
  id: string;
  patientId: string;
  hospitalId: string;
  patientName?: string;
  patientMrn?: string;
  hospitalName?: string;
  /** fillDate + daysSupply, server-computed. */
  expectedDepletionDate?: string;
  createdAt?: string;
  updatedAt?: string;
}

@Injectable({ providedIn: 'root' })
export class MedicationTimelineService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/medication-history';

  timeline(
    patientId: string,
    hospitalId: string,
    startDate?: string,
    endDate?: string,
  ): Observable<MedicationTimelineResponse> {
    let params = new HttpParams().set('hospitalId', hospitalId);
    if (startDate) params = params.set('startDate', startDate);
    if (endDate) params = params.set('endDate', endDate);
    return this.http.get<MedicationTimelineResponse>(
      `${this.baseUrl}/patient/${patientId}/timeline`,
      { params },
    );
  }

  createFill(req: PharmacyFillRequest): Observable<PharmacyFillResponse> {
    return this.http.post<PharmacyFillResponse>(`${this.baseUrl}/pharmacy-fills`, req);
  }

  /** Full replace — round-trip the complete record. */
  updateFill(fillId: string, req: PharmacyFillRequest): Observable<PharmacyFillResponse> {
    return this.http.put<PharmacyFillResponse>(`${this.baseUrl}/pharmacy-fills/${fillId}`, req);
  }

  fillsForPatient(patientId: string, hospitalId: string): Observable<PharmacyFillResponse[]> {
    const params = new HttpParams().set('hospitalId', hospitalId);
    return this.http.get<PharmacyFillResponse[]>(
      `${this.baseUrl}/patient/${patientId}/pharmacy-fills`,
      { params },
    );
  }
}
