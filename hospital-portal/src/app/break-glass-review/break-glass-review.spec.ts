import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { BreakGlassReviewComponent } from './break-glass-review';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { BreakGlassService, BreakGlassSession } from '../services/break-glass.service';

/**
 * E8 #54 — the review page works the queue of the effective hospital and
 * signs sessions off.
 */
describe('BreakGlassReviewComponent', () => {
  let fixture: ComponentFixture<BreakGlassReviewComponent>;
  let component: BreakGlassReviewComponent;
  let service: jasmine.SpyObj<BreakGlassService>;
  let roleContext: RoleContextService;
  let toast: jasmine.SpyObj<ToastService>;

  const h1 = '11111111-1111-1111-1111-111111111111';

  function session(overrides: Partial<BreakGlassSession> = {}): BreakGlassSession {
    return {
      id: 's-1',
      patientId: '22222222-2222-2222-2222-222222222222',
      userId: 'u-1',
      userName: 'dr.alice',
      hospitalId: h1,
      hospitalName: 'CHU Yalgado',
      reason: 'Unconscious in the ED, no proxy reachable',
      startedAt: '2026-09-12T08:00:00',
      expiresAt: '2026-09-12T12:00:00',
      revokedAt: null,
      revokedByUserId: null,
      revokeReason: null,
      auditCount: 3,
      live: false,
      reviewedAt: null,
      reviewedByUserId: null,
      reviewedByUserName: null,
      reviewOutcome: null,
      reviewNote: null,
      reviewed: false,
      ...overrides,
    };
  }

  beforeEach(async () => {
    service = jasmine.createSpyObj<BreakGlassService>('BreakGlassService', [
      'listForHospital',
      'review',
    ]);
    service.listForHospital.and.returnValue(
      of({ content: [session()], totalElements: 1, totalPages: 1 }),
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);

    await TestBed.configureTestingModule({
      imports: [BreakGlassReviewComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        RoleContextService,
        { provide: BreakGlassService, useValue: service },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(['ROLE_HOSPITAL_ADMIN']);
    roleContext.activeHospitalId = h1;
    fixture = TestBed.createComponent(BreakGlassReviewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads the review queue of the effective hospital on open', () => {
    expect(service.listForHospital).toHaveBeenCalledWith(h1, 0, 20, false);
    expect(component.sessions().length).toBe(1);
    expect(component.unreviewedCount()).toBe(1);
  });

  it('switches to the whole register when the queue filter is turned off', () => {
    component.setQueueOnly(false);
    expect(service.listForHospital).toHaveBeenCalledWith(h1, 0, 20, undefined);
  });

  it('signs a session off with the outcome and note, and refreshes the queue', () => {
    const reviewed = session({
      reviewed: true,
      reviewOutcome: 'NOT_JUSTIFIED',
      reviewNote: 'No emergency on the record',
      reviewedByUserName: 'admin.kone',
      reviewedAt: '2026-09-12T15:00:00',
    });
    service.review.and.returnValue(of(reviewed));

    component.openReview(session());
    component.outcome.set('NOT_JUSTIFIED');
    component.note.set('No emergency on the record');
    component.submitReview();

    expect(service.review).toHaveBeenCalledWith('s-1', {
      outcome: 'NOT_JUSTIFIED',
      note: 'No emergency on the record',
    });
    expect(component.reviewing()).toBeNull();
    expect(toast.success).toHaveBeenCalled();
    // the queue is re-read so the signed-off row leaves it
    expect(service.listForHospital).toHaveBeenCalledTimes(2);
  });

  it('reports a failed sign-off and keeps the form open', () => {
    service.review.and.returnValue(throwError(() => ({ error: { message: 'nope' } })));

    component.openReview(session());
    component.submitReview();

    expect(toast.error).toHaveBeenCalledWith('nope');
    expect(component.reviewing()).toBe('s-1');
  });

  it('asks for a hospital scope instead of loading when a super-admin is in global view', () => {
    service.listForHospital.calls.reset();
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.markSuperAdminGlobalDefaults();
    fixture.detectChanges();

    expect(component.scopeReady()).toBeFalse();
    expect(service.listForHospital).not.toHaveBeenCalled();
    expect(component.sessions()).toEqual([]);
  });
});
