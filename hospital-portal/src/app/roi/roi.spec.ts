import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { RoiComponent } from './roi';
import { RoiPage, RoiRequest, RoiService } from '../services/roi.service';
import { PatientService } from '../services/patient.service';
import { HospitalService } from '../services/hospital.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

function request(overrides: Partial<RoiRequest> = {}): RoiRequest {
  return {
    id: 'r1',
    patientId: 'p1',
    patientName: 'Awa Traore',
    requesterType: 'THIRD_PARTY',
    requesterName: 'Cabinet Ouédraogo',
    purpose: 'Insurance claim',
    scopeDescription: 'Full record',
    status: 'PENDING',
    requestedOn: '2026-09-01',
    ...overrides,
  };
}

function page(rows: RoiRequest[]): RoiPage {
  return { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 200 };
}

/**
 * Release of information (Tier 2 item 39b). Pins: the deny reason is
 * required before the button enables; an outage renders "unavailable",
 * never an empty queue; a global-view super-admin gets the pick-a-hospital
 * state with zero requests fired; and the switchMap race — a late response
 * from a previous scope or status must never overwrite the current view,
 * because a super-admin unpinning a hospital would otherwise see the old
 * hospital's PHI repopulate a cleared table.
 */
describe('RoiComponent', () => {
  let fixture: ComponentFixture<RoiComponent>;
  let component: RoiComponent;
  let roiService: jasmine.SpyObj<RoiService>;
  let toast: jasmine.SpyObj<ToastService>;
  // A signal, not a closure variable: the component reads the scope inside
  // a computed(), which only re-evaluates on a signal dependency change —
  // the mid-flight unpin test needs that invalidation to actually happen.
  let scopedHospitalId: WritableSignal<string | null>;
  let decisionRoles: boolean;

  beforeEach(async () => {
    scopedHospitalId = signal<string | null>('h1');
    decisionRoles = true;
    roiService = jasmine.createSpyObj<RoiService>('RoiService', [
      'worklist',
      'create',
      'fulfil',
      'deny',
      'cancel',
      'patientRequests',
    ]);
    roiService.worklist.and.returnValue(of(page([request()])));
    roiService.create.and.returnValue(of(request()));
    roiService.fulfil.and.returnValue(of(request({ status: 'FULFILLED' })));
    roiService.deny.and.returnValue(of(request({ status: 'DENIED' })));

    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);
    const hospitalSpy = jasmine.createSpyObj('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalSpy.list.and.returnValue(of([]));
    const scopeUrlSpy = jasmine.createSpyObj('HospitalScopeUrlService', ['applyUrlScopeSync']);

    await TestBed.configureTestingModule({
      imports: [RoiComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: RoiService, useValue: roiService },
        {
          provide: PatientService,
          useValue: { search: () => of([]), lookup: () => of([]), list: () => of([]) },
        },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: HospitalScopeUrlService, useValue: scopeUrlSpy },
        {
          provide: RoleContextService,
          useValue: {
            effectiveHospitalIdForRequest: () => scopedHospitalId(),
            activeHospitalId: 'h1',
            isSuperAdmin: () => false,
            globalView: () => scopedHospitalId() === null,
            selectedHospitalId: () => scopedHospitalId(),
            permittedHospitalIds: ['h1'],
            hasAnyActiveRole: () => decisionRoles,
          },
        },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RoiComponent);
    component = fixture.componentInstance;
  });

  it('loads the PENDING queue on init', () => {
    fixture.detectChanges();
    expect(roiService.worklist).toHaveBeenCalledWith('PENDING', 0, 200);
    expect(component.rows().length).toBe(1);
  });

  it('a global-view super-admin gets the pick-a-hospital state — no requests fired', () => {
    scopedHospitalId.set(null);
    fixture.detectChanges();
    expect(roiService.worklist).not.toHaveBeenCalled();
    expect(component.scopeReady()).toBeFalse();
  });

  it('an outage renders unavailable — never an empty queue', () => {
    roiService.worklist.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    fixture.detectChanges();
    expect(component.loadFailed()).toBeTrue();
    expect(component.rows().length).toBe(0);
  });

  it('unpinning the scope mid-flight drops the old hospital response — PHI never repopulates', () => {
    const slow = new Subject<RoiPage>();
    roiService.worklist.and.returnValue(slow.asObservable());
    fixture.detectChanges(); // init fires the PENDING load; response still in flight

    scopedHospitalId.set(null); // super-admin returns to global view
    component.onScopeChange(null);
    slow.next(page([request()])); // the previous hospital's rows arrive late
    slow.complete();

    expect(component.rows().length).toBe(0);
    expect(component.scopeReady()).toBeFalse();
  });

  it('a slow response for the previous status never overwrites the newer one', () => {
    const slowPending = new Subject<RoiPage>();
    roiService.worklist.and.returnValue(slowPending.asObservable());
    fixture.detectChanges(); // PENDING load in flight

    roiService.worklist.and.returnValue(of(page([request({ id: 'r2', status: 'DENIED' })])));
    component.setStatus('DENIED'); // resolves immediately
    slowPending.next(page([request(), request({ id: 'r9' })])); // stale PENDING arrives late
    slowPending.complete();

    expect(component.rows().map((r) => r.id)).toEqual(['r2']);
  });

  it('the deny decision is invalid until a reason is typed', () => {
    fixture.detectChanges();
    component.openDecision(request(), 'deny');
    expect(component.decisionValid()).toBeFalse();
    component.decisionNote.set('No authorisation presented');
    expect(component.decisionValid()).toBeTrue();
  });

  it('fulfil needs no note and reloads the queue on success', () => {
    fixture.detectChanges();
    component.openDecision(request(), 'fulfil');
    expect(component.decisionValid()).toBeTrue();
    roiService.worklist.calls.reset();

    component.submitDecision();

    expect(roiService.fulfil).toHaveBeenCalledWith('r1', undefined);
    expect(roiService.worklist).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
  });

  it('intake submits patient + purpose + scope', () => {
    fixture.detectChanges();
    component.openIntake();
    component.onPatientSelected({ id: 'p9' } as never);
    component.formPurpose.set('Transfer of care');
    component.formScope.set('Full record');

    component.submitIntake();

    expect(roiService.create).toHaveBeenCalledWith(
      'p9',
      jasmine.objectContaining({
        requesterType: 'THIRD_PARTY',
        purpose: 'Transfer of care',
        scopeDescription: 'Full record',
      }),
    );
  });
});
