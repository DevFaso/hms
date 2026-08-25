import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { BedBoardComponent } from './bed-board';
import {
  BedBoard,
  BedBoardEntry,
  BedBoardService,
  BedOccupant,
  WardBoard,
} from '../services/bed-board.service';
import { IsolationPrecautionResponse, IsolationService } from '../services/isolation.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

function occupant(overrides: Partial<BedOccupant> = {}): BedOccupant {
  return {
    admissionId: 'a1',
    patientId: 'p1',
    patientName: 'Awa Traoré',
    mrn: 'MRN-1',
    admittedAt: '2026-08-22T08:00:00',
    expectedDischargeAt: null,
    lengthOfStayDays: 3,
    attendingPhysicianName: 'Dr Diallo',
    primaryDiagnosis: 'Pneumonia',
    isolationPrecautions: [],
    requiresIsolationWard: false,
    isolationMismatch: false,
    ...overrides,
  };
}

function bed(overrides: Partial<BedBoardEntry> = {}): BedBoardEntry {
  return {
    bedId: 'b1',
    bedNumber: '1',
    bedType: null,
    status: 'AVAILABLE',
    notes: null,
    occupant: null,
    ...overrides,
  };
}

function ward(overrides: Partial<WardBoard> = {}): WardBoard {
  return {
    wardId: 'w1',
    wardName: 'Maternity A',
    wardCode: 'MATA',
    wardType: 'MATERNITY',
    floor: 1,
    totalBeds: 1,
    occupiedBeds: 0,
    availableBeds: 1,
    occupancyRate: 0,
    isolationCapable: false,
    rooms: [{ roomNumber: '101', beds: [bed()] }],
    ...overrides,
  };
}

function board(overrides: Partial<BedBoard> = {}): BedBoard {
  return {
    hospitalId: 'h1',
    generatedAt: '2026-08-25T09:00:00',
    census: {
      totalBeds: 1,
      occupiedBeds: 0,
      availableBeds: 1,
      reservedBeds: 0,
      outOfServiceBeds: 0,
      occupancyRate: 0,
      inpatientCount: 0,
      orphanedOccupiedBeds: 0,
      expectedDischargesToday: 0,
      patientsOnIsolation: 0,
    },
    wards: [ward()],
    ...overrides,
  };
}

describe('BedBoardComponent', () => {
  let component: BedBoardComponent;
  let boardSpy: jasmine.SpyObj<BedBoardService>;
  let isolationSpy: jasmine.SpyObj<IsolationService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    boardSpy = jasmine.createSpyObj('BedBoardService', ['getBoard']);
    boardSpy.getBoard.and.returnValue(of(board()));

    isolationSpy = jasmine.createSpyObj('IsolationService', [
      'startPrecaution',
      'discontinuePrecaution',
      'getActiveForPatient',
      'getHistoryForPatient',
    ]);
    isolationSpy.getActiveForPatient.and.returnValue(of([]));

    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const roleCtx = {
      hasAnyActiveRole: () => true,
      isSuperAdmin: () => false,
      activeHospitalId: 'h1',
    } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [BedBoardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BedBoardService, useValue: boardSpy },
        { provide: IsolationService, useValue: isolationSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    }).compileComponents();

    component = TestBed.createComponent(BedBoardComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads the board on init', () => {
    component.ngOnInit();
    expect(boardSpy.getBoard).toHaveBeenCalled();
    expect(component.board()?.wards.length).toBe(1);
  });

  it('surfaces a load failure rather than rendering an empty ward', () => {
    // An empty board and an unreachable board look identical to an operator
    // who cannot tell whether to escalate.
    boardSpy.getBoard.and.returnValue(throwError(() => new Error('500')));
    component.ngOnInit();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.board()).toBeNull();
  });

  it('collects every isolation mismatch across the whole board', () => {
    const mismatched = occupant({ isolationMismatch: true, requiresIsolationWard: true });
    boardSpy.getBoard.and.returnValue(
      of(
        board({
          wards: [
            ward({
              rooms: [
                { roomNumber: '101', beds: [bed({ status: 'OCCUPIED', occupant: mismatched })] },
              ],
            }),
          ],
        }),
      ),
    );
    component.ngOnInit();

    expect(component.mismatches().length).toBe(1);
    expect(component.mismatches()[0].patientName).toBe('Awa Traoré');
  });

  it('reports beds stuck occupied with nobody in them', () => {
    boardSpy.getBoard.and.returnValue(
      of(board({ census: { ...board().census, occupiedBeds: 1, orphanedOccupiedBeds: 1 } })),
    );
    component.ngOnInit();
    expect(component.hasOrphanedBeds).toBeTrue();
  });

  it('filters to free beds only', () => {
    boardSpy.getBoard.and.returnValue(
      of(
        board({
          wards: [
            ward({
              rooms: [
                {
                  roomNumber: '101',
                  beds: [bed({ bedId: 'free' }), bed({ bedId: 'taken', status: 'OCCUPIED' })],
                },
              ],
            }),
          ],
        }),
      ),
    );
    component.ngOnInit();

    component.showOnlyAvailable.set(true);
    const beds = component.visibleWards()[0].rooms[0].beds;
    expect(beds.length).toBe(1);
    expect(beds[0].bedId).toBe('free');
  });

  it('filters to isolation patients only, and drops wards left with no beds', () => {
    boardSpy.getBoard.and.returnValue(
      of(
        board({
          wards: [
            ward({
              rooms: [
                { roomNumber: '101', beds: [bed({ status: 'OCCUPIED', occupant: occupant() })] },
              ],
            }),
          ],
        }),
      ),
    );
    component.ngOnInit();

    component.showOnlyIsolation.set(true);
    // The one occupant carries no precautions, so nothing should remain —
    // and an empty ward heading with no beds under it is just noise.
    expect(component.visibleWards().length).toBe(0);
  });

  it('marks a mismatched bed distinctly from an ordinary isolation bed', () => {
    const mismatch = bed({
      status: 'OCCUPIED',
      occupant: occupant({ isolationPrecautions: ['AIRBORNE'], isolationMismatch: true }),
    });
    const contained = bed({
      status: 'OCCUPIED',
      occupant: occupant({ isolationPrecautions: ['CONTACT'] }),
    });

    expect(component.bedStatusClass(mismatch)).toContain('bed-mismatch');
    expect(component.bedStatusClass(contained)).toContain('bed-isolation');
    expect(component.bedStatusClass(bed())).toContain('bed-available');
  });

  it('loads the patient precautions when the modal opens', () => {
    component.openPrecautions(occupant());
    expect(isolationSpy.getActiveForPatient).toHaveBeenCalledWith('p1');
    expect(component.showPrecautionModal()).toBeTrue();
  });

  it('will not submit a precaution without a reason', () => {
    component.openPrecautions(occupant());
    component.precautionForm.reason = '   ';
    component.submitPrecaution();
    expect(isolationSpy.startPrecaution).not.toHaveBeenCalled();
  });

  it('sends the precaution against the occupant admission and reloads the board', () => {
    isolationSpy.startPrecaution.and.returnValue(of({ id: 'i1' } as IsolationPrecautionResponse));
    component.ngOnInit();
    expect(boardSpy.getBoard).toHaveBeenCalledTimes(1);

    component.openPrecautions(occupant());
    component.precautionForm.precautionType = 'AIRBORNE';
    component.precautionForm.reason = 'Suspected TB';
    component.submitPrecaution();

    const [payload] = isolationSpy.startPrecaution.calls.mostRecent().args;
    expect(payload.patientId).toBe('p1');
    expect(payload.admissionId).toBe('a1');
    expect(payload.precautionType).toBe('AIRBORNE');
    expect(component.showPrecautionModal()).toBeFalse();
    // The board must reload: a new airborne precaution can turn the bed the
    // patient is already in into a mismatch.
    expect(boardSpy.getBoard).toHaveBeenCalledTimes(2);
  });

  it('does not discontinue when no reason is given', () => {
    spyOn(window, 'prompt').and.returnValue('');
    component.discontinue({ id: 'i1' } as IsolationPrecautionResponse);
    expect(isolationSpy.discontinuePrecaution).not.toHaveBeenCalled();
  });

  it('passes the lifting reason through', () => {
    spyOn(window, 'prompt').and.returnValue('Culture negative');
    isolationSpy.discontinuePrecaution.and.returnValue(
      of({ id: 'i1' } as IsolationPrecautionResponse),
    );
    component.openPrecautions(occupant());
    component.discontinue({ id: 'i1' } as IsolationPrecautionResponse);

    const [, payload] = isolationSpy.discontinuePrecaution.calls.mostRecent().args;
    expect(payload.discontinuationReason).toBe('Culture negative');
  });
});
