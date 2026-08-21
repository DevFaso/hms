import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { NEVER, of, throwError } from 'rxjs';

import { RefillApprovalListComponent } from './refill-approval-list.component';
import {
  RefillApprovalService,
  RefillRequest,
  RefillStatus,
} from '../services/refill-approval.service';
import { ToastService } from '../core/toast.service';

describe('RefillApprovalListComponent', () => {
  let fixture: ComponentFixture<RefillApprovalListComponent>;
  let component: RefillApprovalListComponent;
  let service: jasmine.SpyObj<RefillApprovalService>;
  let toast: jasmine.SpyObj<ToastService>;

  function refill(overrides: Partial<RefillRequest> = {}): RefillRequest {
    return {
      id: 'r1',
      prescriptionId: 'rx1',
      medicationName: 'Metformin 500mg',
      patientId: 'p1',
      status: 'REQUESTED',
      preferredPharmacy: 'CVS',
      notes: 'running low',
      providerNotes: null,
      requestedAt: '2026-05-01T10:00:00',
      updatedAt: '2026-05-01T10:00:00',
      ...overrides,
    };
  }

  function page(items: RefillRequest[]) {
    return of({
      content: items,
      totalElements: items.length,
      totalPages: 1,
      size: 50,
      number: 0,
    });
  }

  /** Reaches past `protected` — the spec exercises the same surface the template does. */
  interface TemplateApi {
    approve(r: RefillRequest): void;
    reject(r: RefillRequest): void;
    pause(r: RefillRequest): void;
    isOpen(s: RefillStatus): boolean;
    canPause(s: RefillStatus): boolean;
    statusBadgeClass(s: RefillStatus): string;
    decisionNotes: Record<string, string>;
    setFilter(v: RefillStatus | 'ALL'): void;
  }

  function api(): TemplateApi {
    return component as unknown as TemplateApi;
  }

  beforeEach(async () => {
    service = jasmine.createSpyObj<RefillApprovalService>('RefillApprovalService', [
      'list',
      'approve',
      'reject',
      'pause',
    ]);
    service.list.and.returnValue(page([]));
    service.approve.and.returnValue(of(refill({ status: 'APPROVED' })));
    service.reject.and.returnValue(of(refill({ status: 'DENIED' })));
    service.pause.and.returnValue(of(refill({ status: 'PAUSED' })));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [RefillApprovalListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: RefillApprovalService, useValue: service },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RefillApprovalListComponent);
    component = fixture.componentInstance;
  });

  it('loads the pending queue on init', () => {
    fixture.detectChanges();
    expect(service.list).toHaveBeenCalledWith('REQUESTED', 0, 50);
  });

  it('renders the empty state when the queue is clear', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="refill-list-empty"]')).not.toBeNull();
  });

  it('surfaces an error state when the queue cannot be loaded', () => {
    service.list.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="refill-list-error"]')).not.toBeNull();
  });

  it('sends no status param for the ALL filter', () => {
    fixture.detectChanges();
    api().setFilter('ALL');
    expect(service.list).toHaveBeenCalledWith(undefined, 0, 50);
  });

  it('offers a PAUSED filter so held requests stay findable', () => {
    fixture.detectChanges();
    api().setFilter('PAUSED');
    expect(service.list).toHaveBeenCalledWith('PAUSED', 0, 50);
  });

  // ── The decision actions ────────────────────────────────────

  it('approves without notes, which the backend allows', () => {
    fixture.detectChanges();
    api().approve(refill());

    expect(service.approve).toHaveBeenCalledWith('r1', {});
    expect(toast.success).toHaveBeenCalled();
  });

  it('passes provider notes through on approval when they were typed', () => {
    fixture.detectChanges();
    api().decisionNotes['r1'] = '  pick up by Friday  ';
    api().approve(refill());

    expect(service.approve).toHaveBeenCalledWith('r1', { providerNotes: 'pick up by Friday' });
  });

  it('refuses to reject without a reason and never calls the server', () => {
    fixture.detectChanges();
    api().reject(refill());

    expect(service.reject).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('refuses to pause without a reason — the patient is shown that note', () => {
    fixture.detectChanges();
    api().pause(refill());

    expect(service.pause).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('pauses with the typed reason and reloads the queue', () => {
    fixture.detectChanges();
    service.list.calls.reset();
    api().decisionNotes['r1'] = 'Need an A1c first';
    api().pause(refill());

    expect(service.pause).toHaveBeenCalledWith('r1', { providerNotes: 'Need an A1c first' });
    expect(toast.success).toHaveBeenCalled();
    expect(service.list).toHaveBeenCalled();
  });

  it('reports a failed decision instead of silently reloading', () => {
    service.pause.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    api().decisionNotes['r1'] = 'Need an A1c first';
    api().pause(refill());

    expect(toast.error).toHaveBeenCalled();
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('ignores a second action while one is in flight', () => {
    fixture.detectChanges();
    // A call that never settles keeps busyId set.
    service.approve.and.returnValue(NEVER);
    api().approve(refill());
    api().approve(refill({ id: 'r2' }));

    expect(service.approve).toHaveBeenCalledTimes(1);
  });

  // ── Which cards stay actionable ─────────────────────────────

  it('keeps a paused request open to a decision', () => {
    expect(api().isOpen('PAUSED')).toBeTrue();
    expect(api().isOpen('REQUESTED')).toBeTrue();
  });

  it('closes the action row once a request is decided', () => {
    expect(api().isOpen('APPROVED')).toBeFalse();
    expect(api().isOpen('DENIED')).toBeFalse();
    expect(api().isOpen('CANCELLED')).toBeFalse();
    expect(api().isOpen('DISPENSED')).toBeFalse();
  });

  it('hides the hold button on an already-held request', () => {
    expect(api().canPause('REQUESTED')).toBeTrue();
    expect(api().canPause('PAUSED')).toBeFalse();
  });

  it('renders the action row for a paused card, hold button excluded', () => {
    service.list.and.returnValue(page([refill({ status: 'PAUSED' })]));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="refill-approve-r1"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="refill-reject-r1"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="refill-pause-r1"]')).toBeNull();
  });

  it('renders all three actions on a pending card', () => {
    service.list.and.returnValue(page([refill()]));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="refill-pause-r1"]')).not.toBeNull();
  });

  it('gives a paused request its own badge, distinct from pending', () => {
    expect(api().statusBadgeClass('PAUSED')).not.toBe(api().statusBadgeClass('REQUESTED'));
  });
});
