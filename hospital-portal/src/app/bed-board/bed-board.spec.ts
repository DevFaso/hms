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
import { TransferOrderResponse, TransferService } from '../services/transfer.service';
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

function transferOrder(overrides: Partial<TransferOrderResponse> = {}): TransferOrderResponse {
  return {
    id: 't1',
    admissionId: 'a1',
    patientId: 'p1',
    patientName: 'Awa Traoré',
    mrn: 'MRN-1',
    fromBedId: 'b1',
    fromBedLabel: 'MATA/1',
    fromWardName: 'Maternity A',
    toBedId: 'b2',
    toBedLabel: 'MATA/2',
    toWardName: 'Maternity A',
    transferType: 'BED_TO_BED',
    status: 'REQUESTED',
    reason: 'Needs closer observation',
    notes: null,
    requestedByName: null,
    requestedAt: '2026-08-25T08:00:00',
    completedByName: null,
    completedAt: null,
    cancelledByName: null,
    cancelledAt: null,
    cancellationReason: null,
    isolationOverride: false,
    isolationOverrideReason: null,
    isolationPrecautions: [],
    destinationIsolationMismatch: false,
    ...overrides,
  };
}

describe('BedBoardComponent', () => {
  let component: BedBoardComponent;
  let boardSpy: jasmine.SpyObj<BedBoardService>;
  let isolationSpy: jasmine.SpyObj<IsolationService>;
  let transferSpy: jasmine.SpyObj<TransferService>;
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

    transferSpy = jasmine.createSpyObj('TransferService', [
      'requestTransfer',
      'completeTransfer',
      'cancelTransfer',
      'getPending',
      'getHistoryForAdmission',
    ]);
    transferSpy.getPending.and.returnValue(of([]));

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
        { provide: TransferService, useValue: transferSpy },
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

  // ── Transfers (item 30) ────────────────────────────────────────────

  it('loads the transfer worklist on init', () => {
    component.ngOnInit();
    expect(transferSpy.getPending).toHaveBeenCalled();
  });

  it('offers only free beds as destinations', () => {
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

    expect(component.destinationOptions().map((o) => o.bedId)).toEqual(['free']);
  });

  it('excludes a bed another transfer is already on its way to', () => {
    // The backend refuses it, so offering it would be an error waiting.
    boardSpy.getBoard.and.returnValue(
      of(
        board({
          wards: [ward({ rooms: [{ roomNumber: '101', beds: [bed({ bedId: 'spoken' })] }] })],
        }),
      ),
    );
    transferSpy.getPending.and.returnValue(of([transferOrder({ toBedId: 'spoken' })]));
    component.ngOnInit();

    expect(component.destinationOptions()).toEqual([]);
  });

  it('will not submit a transfer with no destination or no reason', () => {
    component.openTransfer(occupant());
    expect(component.transferSubmittable).toBeFalse();

    component.transferForm.toBedId = 'b2';
    expect(component.transferSubmittable).toBeFalse();

    component.transferForm.reason = 'Closer observation';
    expect(component.transferSubmittable).toBeTrue();
  });

  it('warns before submitting when the destination cannot contain an airborne case', () => {
    // The operator is told up front rather than meeting a server refusal.
    boardSpy.getBoard.and.returnValue(
      of(
        board({
          wards: [
            ward({
              isolationCapable: false,
              rooms: [{ roomNumber: '101', beds: [bed({ bedId: 'general' })] }],
            }),
          ],
        }),
      ),
    );
    component.ngOnInit();
    component.openTransfer(occupant({ requiresIsolationWard: true }));
    component.transferForm.toBedId = 'general';
    component.transferForm.reason = 'Needs theatre';

    expect(component.destinationNeedsOverride()).toBeTrue();
    // Blocked until the override is acknowledged AND explained.
    expect(component.transferSubmittable).toBeFalse();
    component.transferForm.isolationOverride = true;
    expect(component.transferSubmittable).toBeFalse();
    component.transferForm.isolationOverrideReason = 'No isolation bed free';
    expect(component.transferSubmittable).toBeTrue();
  });

  it('does not warn when the destination is an isolation ward', () => {
    boardSpy.getBoard.and.returnValue(
      of(
        board({
          wards: [
            ward({
              isolationCapable: true,
              rooms: [{ roomNumber: '201', beds: [bed({ bedId: 'iso' })] }],
            }),
          ],
        }),
      ),
    );
    component.ngOnInit();
    component.openTransfer(occupant({ requiresIsolationWard: true }));
    component.transferForm.toBedId = 'iso';

    expect(component.destinationNeedsOverride()).toBeFalse();
  });

  it('sends the override and its reason when one is needed', () => {
    boardSpy.getBoard.and.returnValue(
      of(
        board({
          wards: [
            ward({
              isolationCapable: false,
              rooms: [{ roomNumber: '101', beds: [bed({ bedId: 'general' })] }],
            }),
          ],
        }),
      ),
    );
    transferSpy.requestTransfer.and.returnValue(of(transferOrder()));
    component.ngOnInit();
    component.openTransfer(occupant({ requiresIsolationWard: true }));
    component.transferForm.toBedId = 'general';
    component.transferForm.reason = 'Needs theatre';
    component.transferForm.isolationOverride = true;
    component.transferForm.isolationOverrideReason = 'No isolation bed free';
    component.submitTransfer();

    const [sent] = transferSpy.requestTransfer.calls.mostRecent().args;
    expect(sent.isolationOverride).toBeTrue();
    expect(sent.isolationOverrideReason).toBe('No isolation bed free');
  });

  it('does not send an override when the destination is fine', () => {
    transferSpy.requestTransfer.and.returnValue(of(transferOrder()));
    component.ngOnInit();
    component.openTransfer(occupant());
    component.transferForm.toBedId = 'b2';
    component.transferForm.reason = 'Closer observation';
    component.submitTransfer();

    const [sent] = transferSpy.requestTransfer.calls.mostRecent().args;
    expect(sent.isolationOverride).toBeUndefined();
    expect(sent.isolationOverrideReason).toBeUndefined();
  });

  it('reloads both the board and the worklist after a transfer is ordered', () => {
    // A transfer changes the grid AND the set of held beds.
    transferSpy.requestTransfer.and.returnValue(of(transferOrder()));
    component.ngOnInit();
    component.openTransfer(occupant());
    component.transferForm.toBedId = 'b2';
    component.transferForm.reason = 'Closer observation';
    component.submitTransfer();

    expect(boardSpy.getBoard).toHaveBeenCalledTimes(2);
    expect(transferSpy.getPending).toHaveBeenCalledTimes(2);
  });

  it('carries out a waiting transfer', () => {
    transferSpy.completeTransfer.and.returnValue(of(transferOrder({ status: 'COMPLETED' })));
    component.completeTransfer(transferOrder());
    expect(transferSpy.completeTransfer).toHaveBeenCalledWith('t1', {});
  });

  it('does not cancel a transfer when no reason is given', () => {
    spyOn(window, 'prompt').and.returnValue('');
    component.cancelTransfer(transferOrder());
    expect(transferSpy.cancelTransfer).not.toHaveBeenCalled();
  });

  it('passes the cancellation reason through', () => {
    spyOn(window, 'prompt').and.returnValue('Patient improved');
    transferSpy.cancelTransfer.and.returnValue(of(transferOrder({ status: 'CANCELLED' })));
    component.cancelTransfer(transferOrder());

    const [, sent] = transferSpy.cancelTransfer.calls.mostRecent().args;
    expect(sent.cancellationReason).toBe('Patient improved');
  });
});
