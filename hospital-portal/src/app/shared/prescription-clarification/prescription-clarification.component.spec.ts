import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { PrescriptionClarificationComponent } from './prescription-clarification.component';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { PrescriptionResponse, PrescriptionService } from '../../services/prescription.service';

/**
 * Gap G5. The point of these specs is the ROLE GATE and the CONSEQUENCE
 * notice: a control that renders for a role the endpoint refuses is a 403
 * waiting to happen, and a pharmacist who loses a row off their queue
 * without being told is the failure this feature exists to avoid.
 */
describe('PrescriptionClarificationComponent', () => {
  let fixture: ComponentFixture<PrescriptionClarificationComponent>;
  let component: PrescriptionClarificationComponent;
  let prescriptions: jasmine.SpyObj<PrescriptionService>;
  let toast: jasmine.SpyObj<ToastService>;
  let activeRoles: string[];

  const el = (testId: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);

  interface Inputs {
    mode: 'PHARMACY' | 'PRESCRIBER';
    prescriptionId?: string;
    status?: string | null;
    medicationLabel?: string | null;
    attentionReason?: string | null;
    question?: string | null;
    askedAt?: string | null;
  }

  function create(roles: string[], inputs: Inputs): void {
    activeRoles = roles;
    fixture = TestBed.createComponent(PrescriptionClarificationComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('mode', inputs.mode);
    fixture.componentRef.setInput('prescriptionId', inputs.prescriptionId ?? 'rx-1');
    fixture.componentRef.setInput('status', inputs.status ?? 'SIGNED');
    fixture.componentRef.setInput('medicationLabel', inputs.medicationLabel ?? 'Amoxicillin');
    fixture.componentRef.setInput('attentionReason', inputs.attentionReason ?? null);
    fixture.componentRef.setInput('question', inputs.question ?? null);
    fixture.componentRef.setInput('askedAt', inputs.askedAt ?? null);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    activeRoles = ['ROLE_PHARMACIST'];
    prescriptions = jasmine.createSpyObj('PrescriptionService', [
      'getById',
      'requestClarification',
      'resolveClarification',
    ]);
    prescriptions.requestClarification.and.returnValue(of({} as PrescriptionResponse));
    prescriptions.resolveClarification.and.returnValue(of({} as PrescriptionResponse));
    prescriptions.getById.and.returnValue(of({} as PrescriptionResponse));
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);

    await TestBed.configureTestingModule({
      imports: [PrescriptionClarificationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PrescriptionService, useValue: prescriptions },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
          },
        },
      ],
    }).compileComponents();
  });

  /* ── Who may raise a clarification ─────────────────────────────────── */

  it('offers the control to a pharmacist', () => {
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    expect(el('rx-clarification-open-rx-1')).not.toBeNull();
  });

  it('offers the control to a pharmacy verifier — the role that exists to do this', () => {
    create(['ROLE_PHARMACY_VERIFIER'], { mode: 'PHARMACY' });
    expect(el('rx-clarification-open-rx-1')).not.toBeNull();
  });

  it('is absent for a role the request endpoint would reject', () => {
    create(['ROLE_NURSE'], { mode: 'PHARMACY' });
    expect(el('rx-clarification-open-rx-1')).toBeNull();
  });

  it('is absent on a status the backend refuses a question from', () => {
    // DISPENSED is not in CLARIFIABLE_STATUSES: the order has already been
    // handed over, so the button would only ever earn a 400.
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY', status: 'DISPENSED' });
    expect(el('rx-clarification-open-rx-1')).toBeNull();
  });

  /* ── Who may answer one ────────────────────────────────────────────── */

  it('offers the resolve control to a doctor on a PENDING_CLARIFICATION row', () => {
    create(['ROLE_DOCTOR'], { mode: 'PRESCRIBER', status: 'PENDING_CLARIFICATION' });
    expect(el('rx-clarification-open-rx-1')).not.toBeNull();
  });

  it('does not offer the resolve control to a nurse or a pharmacist', () => {
    create(['ROLE_NURSE'], { mode: 'PRESCRIBER', status: 'PENDING_CLARIFICATION' });
    expect(el('rx-clarification-open-rx-1')).toBeNull();

    create(['ROLE_PHARMACIST'], { mode: 'PRESCRIBER', status: 'PENDING_CLARIFICATION' });
    expect(el('rx-clarification-open-rx-1')).toBeNull();
  });

  it('does not offer the resolve control on a row with no question open', () => {
    create(['ROLE_DOCTOR'], { mode: 'PRESCRIBER', status: 'SIGNED' });
    expect(el('rx-clarification-open-rx-1')).toBeNull();
  });

  /* ── Raising one ───────────────────────────────────────────────────── */

  it('says that raising a question stops the fill, before the send button', () => {
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    const consequence = el('rx-clarification-consequence');
    expect(consequence).not.toBeNull();
    expect(consequence!.textContent).toContain('PRESCRIPTIONS.CLARIFICATION.BLOCKS_DISPENSING');
  });

  it('refuses to send an empty question but allows an empty answer', () => {
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();
    expect((el('rx-clarification-submit-rx-1') as HTMLButtonElement).disabled).toBeTrue();

    create(['ROLE_DOCTOR'], { mode: 'PRESCRIBER', status: 'PENDING_CLARIFICATION' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();
    expect((el('rx-clarification-submit-rx-1') as HTMLButtonElement).disabled).toBeFalse();
  });

  it('posts the reason, toasts and asks the host to reload', () => {
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    const changed = jasmine.createSpy('changed');
    component.changed.subscribe(changed);
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    const textarea = el('rx-clarification-text-rx-1') as HTMLTextAreaElement;
    textarea.value = 'Dose exceeds the weight-based maximum.';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    el('rx-clarification-submit-rx-1')!.click();
    fixture.detectChanges();

    expect(prescriptions.requestClarification).toHaveBeenCalledWith(
      'rx-1',
      'Dose exceeds the weight-based maximum.',
    );
    expect(toast.success).toHaveBeenCalled();
    expect(changed).toHaveBeenCalled();
    // The dialog closes on success so the row can leave the queue.
    expect(el('rx-clarification-modal-rx-1')).toBeNull();
  });

  it('posts the answer through the resolve endpoint', () => {
    create(['ROLE_DOCTOR'], { mode: 'PRESCRIBER', status: 'PENDING_CLARIFICATION' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    const textarea = el('rx-clarification-text-rx-1') as HTMLTextAreaElement;
    textarea.value = 'Weight is 82 kg; the dose is correct.';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el('rx-clarification-submit-rx-1')!.click();
    fixture.detectChanges();

    expect(prescriptions.resolveClarification).toHaveBeenCalledWith(
      'rx-1',
      'Weight is 82 kg; the dose is correct.',
    );
  });

  /* ── Error state ───────────────────────────────────────────────────── */

  it("renders the server's refusal inline rather than swallowing it", () => {
    prescriptions.requestClarification.and.returnValue(
      throwError(() => ({ error: { message: 'This prescription is not awaiting a fill.' } })),
    );
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    const textarea = el('rx-clarification-text-rx-1') as HTMLTextAreaElement;
    textarea.value = 'Please confirm the strength.';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el('rx-clarification-submit-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-error')!.textContent).toContain(
      'This prescription is not awaiting a fill.',
    );
    // The dialog stays open with the text intact so the question is not lost.
    expect(el('rx-clarification-modal-rx-1')).not.toBeNull();
  });

  it('renders an explicit error state when the exchange cannot be loaded', () => {
    prescriptions.getById.and.returnValue(throwError(() => new Error('boom')));
    create(['ROLE_PHARMACIST'], {
      mode: 'PHARMACY',
      attentionReason: 'CLARIFICATION_RESOLVED',
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-exchange-error')).not.toBeNull();
    expect(el('rx-clarification-exchange')).toBeNull();
  });

  /* ── Reading the answer ────────────────────────────────────────────── */

  it('shows the prescriber answer once the row comes back resolved', () => {
    prescriptions.getById.and.returnValue(
      of({
        clarificationReason: 'Dose exceeds the weight-based maximum.',
        clarificationResponse: 'Patient is 82 kg; dose confirmed.',
        clarificationRequestedAt: '2026-09-20T09:00:00',
        clarificationResolvedAt: '2026-09-20T11:00:00',
      } as PrescriptionResponse),
    );
    create(['ROLE_PHARMACIST'], {
      mode: 'PHARMACY',
      attentionReason: 'CLARIFICATION_RESOLVED',
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-exchange')!.textContent).toContain(
      'Patient is 82 kg; dose confirmed.',
    );
  });

  it('renders the empty state when the prescriber answered with no note', () => {
    prescriptions.getById.and.returnValue(
      of({ clarificationReason: 'Strength?', clarificationResponse: null } as PrescriptionResponse),
    );
    create(['ROLE_PHARMACIST'], {
      mode: 'PHARMACY',
      attentionReason: 'CLARIFICATION_RESOLVED',
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-answer-empty')).not.toBeNull();
  });

  it('does not fire a read the verifier role is refused, and says why', () => {
    create(['ROLE_PHARMACY_VERIFIER'], {
      mode: 'PHARMACY',
      attentionReason: 'CLARIFICATION_RESOLVED',
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(prescriptions.getById).not.toHaveBeenCalled();
    expect(el('rx-clarification-unreadable')).not.toBeNull();
  });

  it('renders the empty state when a PENDING_CLARIFICATION row carries no question', () => {
    create(['ROLE_DOCTOR'], {
      mode: 'PRESCRIBER',
      status: 'PENDING_CLARIFICATION',
      question: null,
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-question-empty')).not.toBeNull();
  });

  it('shows the pharmacist question to the prescriber', () => {
    create(['ROLE_DOCTOR'], {
      mode: 'PRESCRIBER',
      status: 'PENDING_CLARIFICATION',
      question: 'Is the 500 mg strength intended?',
      askedAt: '2026-09-20T09:00:00',
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-question')!.textContent).toContain(
      'Is the 500 mg strength intended?',
    );
  });

  it('closes on Escape from the dialog, which is where focus lands', () => {
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    const dialog = el('rx-clarification-dialog-rx-1')!;
    // The effect focuses the dialog as it opens, so the key event a real
    // user generates is delivered here and not to the trigger button.
    expect(document.activeElement).toBe(dialog);

    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();

    expect(el('rx-clarification-modal-rx-1')).toBeNull();
  });

  it('names the dialog for assistive technology, uniquely per row', () => {
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY', prescriptionId: 'rx-9' });
    el('rx-clarification-open-rx-9')!.click();
    fixture.detectChanges();

    const dialog = el('rx-clarification-dialog-rx-9')!;
    const labelledBy = dialog.getAttribute('aria-labelledby');
    expect(labelledBy).toBe('rx-clarify-title-rx-9');
    expect(dialog.querySelector('#' + labelledBy)).not.toBeNull();
  });

  /* ── The answer must stay reachable when the flag is masked ────────── */

  it('reads the exchange on every pharmacy open, not only on the resolved flag', () => {
    // The backend resolves ONE attentionReason by precedence, so a question
    // raised on a back-ordered row comes back flagged PENDING_STOCK and the
    // prescriber's answer would otherwise be unreachable from the queue.
    prescriptions.getById.and.returnValue(
      of({
        clarificationReason: 'Confirm the strength.',
        clarificationResponse: 'Strength confirmed at 500 mg.',
      } as PrescriptionResponse),
    );
    create(['ROLE_PHARMACIST'], {
      mode: 'PHARMACY',
      status: 'PENDING_STOCK',
      attentionReason: 'PENDING_STOCK',
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(prescriptions.getById).toHaveBeenCalledWith('rx-1');
    expect(el('rx-clarification-exchange')!.textContent).toContain('Strength confirmed at 500 mg.');
  });

  it('shows no exchange section on a prescription that never had one', () => {
    prescriptions.getById.and.returnValue(of({} as PrescriptionResponse));
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-exchange')).toBeNull();
  });

  /* ── A dismissal must not discard an in-flight refusal ─────────────── */

  it('refuses to close while the post is in flight', () => {
    const pending = new Subject<PrescriptionResponse>();
    prescriptions.requestClarification.and.returnValue(pending.asObservable());
    create(['ROLE_PHARMACIST'], { mode: 'PHARMACY' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    const textarea = el('rx-clarification-text-rx-1') as HTMLTextAreaElement;
    textarea.value = 'Confirm the strength.';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el('rx-clarification-submit-rx-1')!.click();
    fixture.detectChanges();

    // Backdrop click mid-flight: ignored, or the server's refusal would land
    // on a dialog nobody is looking at and be cleared on the next open.
    el('rx-clarification-modal-rx-1')!.click();
    fixture.detectChanges();
    expect(el('rx-clarification-modal-rx-1')).not.toBeNull();

    pending.error({ error: { message: 'This prescription is not awaiting a fill.' } });
    fixture.detectChanges();

    expect(el('rx-clarification-error')!.textContent).toContain(
      'This prescription is not awaiting a fill.',
    );
  });

  it('tells a verifier an unreadable exchange exists even when the flag is masked', () => {
    // attentionReason reports PENDING_STOCK, not CLARIFICATION_RESOLVED, so
    // nothing on the row says an answer is waiting; the verifier still
    // cannot read it and must be told rather than shown a bare form.
    create(['ROLE_PHARMACY_VERIFIER'], {
      mode: 'PHARMACY',
      status: 'PENDING_STOCK',
      attentionReason: 'PENDING_STOCK',
    });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(prescriptions.getById).not.toHaveBeenCalled();
    expect(el('rx-clarification-unreadable')).not.toBeNull();
  });

  it('replaces the backend\'s bare "Access denied" with the rule that was broken', () => {
    // GlobalExceptionHandler collapses every AccessDeniedException to
    // "Access denied", which names neither the rule nor the remedy.
    prescriptions.resolveClarification.and.returnValue(
      throwError(() => ({ status: 403, error: { message: 'Access denied' } })),
    );
    create(['ROLE_DOCTOR'], { mode: 'PRESCRIBER', status: 'PENDING_CLARIFICATION' });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();
    el('rx-clarification-submit-rx-1')!.click();
    fixture.detectChanges();

    const shown = el('rx-clarification-error')!.textContent ?? '';
    expect(shown).toContain('PRESCRIPTIONS.CLARIFICATION.FORBIDDEN_RESOLVE');
    expect(shown).not.toContain('Access denied');
  });

  it('gives a verifier no warning about an exchange that does not exist', () => {
    // A first question on a freshly signed order: no attentionReason, so
    // nothing can be masking a resolved clarification.
    create(['ROLE_PHARMACY_VERIFIER'], { mode: 'PHARMACY', attentionReason: null });
    el('rx-clarification-open-rx-1')!.click();
    fixture.detectChanges();

    expect(el('rx-clarification-unreadable')).toBeNull();
  });
});
