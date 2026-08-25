import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { IsolationPrecautionType } from './isolation.service';

export type BedStatus = 'AVAILABLE' | 'OCCUPIED' | 'RESERVED' | 'MAINTENANCE' | 'OUT_OF_SERVICE';

export type WardType =
  | 'GENERAL'
  | 'SURGICAL'
  | 'MATERNITY'
  | 'PEDIATRIC'
  | 'ICU'
  | 'CCU'
  | 'NICU'
  | 'PSYCHIATRIC'
  | 'ISOLATION'
  | 'PRIVATE'
  | 'SEMI_PRIVATE'
  | 'EMERGENCY'
  | 'RECOVERY';

export interface BedOccupant {
  admissionId: string;
  patientId: string;
  patientName: string | null;
  mrn: string | null;
  admittedAt: string;
  expectedDischargeAt: string | null;
  lengthOfStayDays: number | null;
  attendingPhysicianName: string | null;
  primaryDiagnosis: string | null;
  /** Every precaution in force — concurrent ones are normal. */
  isolationPrecautions: IsolationPrecautionType[];
  requiresIsolationWard: boolean;
  /** Needs an isolation ward and is not in one. The board exists for this. */
  isolationMismatch: boolean;
}

export interface BedBoardEntry {
  bedId: string;
  bedNumber: string;
  bedType: string | null;
  status: BedStatus;
  notes: string | null;
  occupant: BedOccupant | null;
}

export interface RoomBoard {
  /** Null is a real state — beds exist before anyone numbers the bays. */
  roomNumber: string | null;
  beds: BedBoardEntry[];
}

export interface WardBoard {
  wardId: string;
  wardName: string;
  wardCode: string;
  wardType: WardType;
  floor: number | null;
  totalBeds: number;
  occupiedBeds: number;
  availableBeds: number;
  occupancyRate: number;
  /** True when the ward can hold an airborne case. */
  isolationCapable: boolean;
  rooms: RoomBoard[];
}

export interface BedCensus {
  totalBeds: number;
  occupiedBeds: number;
  availableBeds: number;
  reservedBeds: number;
  outOfServiceBeds: number;
  occupancyRate: number;
  /** Counted from admissions, not from bed status. */
  inpatientCount: number;
  /**
   * Beds marked OCCUPIED that no admission points at — usually a half-failed
   * discharge. Reported rather than hidden: such a bed is unallocatable and
   * stays lost for as long as nothing surfaces it.
   */
  orphanedOccupiedBeds: number;
  expectedDischargesToday: number;
  patientsOnIsolation: number;
}

export interface BedBoard {
  hospitalId: string;
  generatedAt: string;
  census: BedCensus;
  wards: WardBoard[];
}

@Injectable({ providedIn: 'root' })
export class BedBoardService {
  private readonly http = inject(HttpClient);

  getBoard(): Observable<BedBoard> {
    return this.http.get<BedBoard>('/bed-board');
  }
}
