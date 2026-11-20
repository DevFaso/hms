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
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

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
    reject(r: RefillRequest): Promise<void>;
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
      'pharmacistReview',
      'pharmacistRefuse',
      'releaseAsPrescribed',
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
    expect(service.list).toHaveBeenCalledWith('REQUESTED', 0, 200); // QA D1347
  });

  // QA D1276 (round 27): three "Paracétamol" cards could not be told apart.
  it('QA D1276: each card names the patient and the MRN', () => {
    service.list.and.returnValue(
      page([
        refill({ id: 'r1', patientName: 'Awa Kaboré', patientMrn: 'CHW9395' }),
        refill({ id: 'r2', patientName: 'Ali Ouédraogo', patientMrn: null }),
        refill({ id: 'r3' }),
      ]),
    );
    fixture.detectChanges();
    const patientOf = (id: string) =>
      (fixture.nativeElement as HTMLElement)
        .querySelector(`[data-testid="refill-card-${id}"] [data-testid="refill-patient"]`)
        ?.textContent?.replace(/\s+/g, ' ')
        .trim();
    expect(patientOf('r1')).toContain('Awa Kaboré (CHW9395)');
    expect(patientOf('r2')).toContain('Ali Ouédraogo');
    expect(patientOf('r2')).not.toContain('(');
    expect(patientOf('r3')).toContain('REFILLS.UNKNOWN_PATIENT');
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
    expect(service.list).toHaveBeenCalledWith(undefined, 0, 200); // QA D1347
  });

  it('offers a PAUSED filter so held requests stay findable', () => {
    fixture.detectChanges();
    api().setFilter('PAUSED');
    expect(service.list).toHaveBeenCalledWith('PAUSED', 0, 200); // QA D1347
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

  it('QA D1305: a refusal without notes marks the card notes box, not only a toast', () => {
    service.list.and.returnValue(page([refill()]));
    fixture.detectChanges();
    api().reject(refill());
    fixture.detectChanges();
    const box = (fixture.nativeElement as HTMLElement).querySelector('#refill-notes-r1')!;
    expect(box.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(box);
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

  it('QA CX-010: refusing a refill asks first even with its reason written; a no sends nothing', async () => {
    fixture.detectChanges();
    const ask = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);
    api().decisionNotes['r1'] = 'Not without a visit';
    await api().reject(refill());
    expect(ask).toHaveBeenCalledTimes(1);
    expect(service.reject).not.toHaveBeenCalled();
  });

  // ── Policy rule 1 (provisional, QA D2308): only a prescriber approves a renewal ─────────────

  describe('policy rule 1: the pharmacy reviews, flags, refuses and releases; it never approves', () => {
    function renderFor(items: RefillRequest[]): HTMLElement {
      service.list.and.returnValue(page(items));
      (component as unknown as { setFilter(v: RefillStatus | 'ALL'): void }).setFilter('REQUESTED');
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    interface PharmacyApi {
      forward(r: RefillRequest): void;
      release(r: RefillRequest): void;
      pharmacistRefuse(r: RefillRequest): Promise<void>;
      decisionNotes: Record<string, string>;
    }

    function pharmacy(): PharmacyApi {
      return component as unknown as PharmacyApi;
    }

    let confirmAsk: jasmine.Spy;

    beforeEach(() => {
      confirmAsk = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    });

    it('no pharmacist-approved renewal: a pharmacist sees no Approve, Hold or Refuse-as-prescriber', () => {
      const host = renderFor([refill({ pharmacyView: true, writtenRefillLeft: false })]);
      expect(host.querySelector('[data-testid="refill-approve-r1"]')).toBeNull();
      expect(host.querySelector('[data-testid="refill-pause-r1"]')).toBeNull();
      expect(host.querySelector('[data-testid="refill-reject-r1"]')).toBeNull();
      expect(host.querySelector('[data-testid="refill-forward-r1"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="refill-pharmacist-refuse-r1"]')).not.toBeNull();
      // No written refill is left: the pharmacy cannot release it either, and the card says who can.
      expect(host.querySelector('[data-testid="refill-release-r1"]')).toBeNull();
      expect(host.querySelector('[data-testid="refill-needs-prescriber"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="refill-pharmacy-rule"]')).not.toBeNull();
    });

    it('a prescriber keeps the three decisions and gets none of the pharmacy steps', () => {
      const host = renderFor([refill({ pharmacyView: false, writtenRefillLeft: true })]);
      expect(host.querySelector('[data-testid="refill-approve-r1"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="refill-pause-r1"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="refill-reject-r1"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="refill-release-r1"]')).toBeNull();
      expect(host.querySelector('[data-testid="refill-forward-r1"]')).toBeNull();
    });

    it('a refill already written on the prescription is released as ordinary dispensing', () => {
      const item = refill({ pharmacyView: true, writtenRefillLeft: true });
      const host = renderFor([item]);
      service.releaseAsPrescribed.and.returnValue(of(item));
      expect(host.querySelector('[data-testid="refill-release-r1"]')).not.toBeNull();
      pharmacy().release(item);
      expect(service.releaseAsPrescribed).toHaveBeenCalledOnceWith('r1');
      expect(service.approve).not.toHaveBeenCalled();
    });

    it('forwarding needs the remarks of the pharmacist, marked on the card, and sends them to the prescriber', () => {
      const item = refill({ pharmacyView: true });
      const host = renderFor([item]);
      service.pharmacistReview.and.returnValue(of(item));
      pharmacy().forward(item);
      expect(service.pharmacistReview).not.toHaveBeenCalled();
      expect(host.querySelector('#refill-notes-r1')!.getAttribute('aria-invalid')).toBe('true');

      pharmacy().decisionNotes['r1'] = '  Demandé 12 jours trop tôt ';
      pharmacy().forward(item);
      expect(service.pharmacistReview).toHaveBeenCalledOnceWith('r1', 'Demandé 12 jours trop tôt');
    });

    it('a refusal to dispense needs its reason and asks first; a no sends nothing', async () => {
      const item = refill({ pharmacyView: true });
      renderFor([item]);
      service.pharmacistRefuse.and.returnValue(of(item));
      await pharmacy().pharmacistRefuse(item);
      expect(service.pharmacistRefuse).not.toHaveBeenCalled();

      pharmacy().decisionNotes['r1'] = 'Interaction avec la warfarine';
      confirmAsk.and.resolveTo(false);
      await pharmacy().pharmacistRefuse(item);
      expect(service.pharmacistRefuse).not.toHaveBeenCalled();

      confirmAsk.and.resolveTo(true);
      await pharmacy().pharmacistRefuse(item);
      expect(service.pharmacistRefuse).toHaveBeenCalledOnceWith(
        'r1',
        'Interaction avec la warfarine',
      );
    });

    it('a decided card shows the note of the pharmacist and on what authority it was decided', () => {
      service.list.and.returnValue(
        page([
          refill({
            status: 'DENIED',
            pharmacyView: true,
            pharmacistNote: 'Interaction avec la warfarine',
            decisionBasis: 'PHARMACIST_REFUSAL',
          }),
        ]),
      );
      (component as unknown as { setFilter(v: RefillStatus | 'ALL'): void }).setFilter('DENIED');
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="refill-pharmacist-note"]')!.textContent).toContain(
        'Interaction avec la warfarine',
      );
      expect(host.querySelector('[data-testid="refill-decision-basis"]')!.textContent).toContain(
        'REFILLS.BASIS_PHARMACIST_REFUSAL',
      );
      expect(host.querySelector('[data-testid="refill-pharmacy-actions"]')).toBeNull();
    });
  });
});
