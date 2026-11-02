import { formatDate } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, Subject, throwError } from 'rxjs';

import { EmarComponent } from './emar.component';
import {
  MarVerificationResponse,
  NurseMedicationTask,
  NurseTaskService,
} from '../../services/nurse-task.service';
import { ToastService } from '../../core/toast.service';
import { mouseClick } from '../../testing/mouse-click';
import { reasonDialog } from '../../shared/reason-dialog/reason-dialog.testing';

describe('EmarComponent (P1 #8 — five-rights barcode-scan loop)', () => {
  let fixture: ComponentFixture<EmarComponent>;
  let component: EmarComponent;
  let nurseSpy: jasmine.SpyObj<NurseTaskService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  const sampleTask: NurseMedicationTask = {
    id: 'task-1',
    patientId: 'pat-1',
    patientName: 'Alice Patient',
    medication: 'Amoxicillin',
    dose: '500 mg',
    route: 'PO',
    dueTime: '2026-04-30T08:00:00',
    status: 'PENDING',
  };

  beforeEach(async () => {
    nurseSpy = jasmine.createSpyObj<NurseTaskService>('NurseTaskService', [
      'getMedicationMAR',
      'verifyMedication',
      'administerMedication',
      'getAdministrationWitnessCandidates',
    ]);
    nurseSpy.getAdministrationWitnessCandidates.and.returnValue(
      of([{ userId: 'u-witness', displayName: 'Awa Kaboré' }]),
    );
    toastSpy = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [EmarComponent, TranslateModule.forRoot()],
      providers: [
        { provide: NurseTaskService, useValue: nurseSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(EmarComponent);
    component = fixture.componentInstance;
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the loading state immediately and the empty state when no doses are due', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([]));

    fixture.detectChanges();

    expect(root().querySelector('[data-testid="emar-empty"]')).not.toBeNull();
    expect(nurseSpy.getMedicationMAR).toHaveBeenCalledOnceWith({ status: 'PENDING' });
  });

  it('renders the queue when due doses arrive', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));

    fixture.detectChanges();

    expect(root().querySelector('[data-testid="emar-queue"]')).not.toBeNull();
    expect(root().querySelector(`[data-testid="emar-task-${sampleTask.id}"]`)).not.toBeNull();
  });

  it("QA D647: shows the due time in the reader's local zone, not the server's UTC clock", () => {
    // The server sends a zone-less UTC wall time; the header clock is local.
    // QA D949: a dose of another day shows its date, so the zone is checked on
    // a time of the reader's today (one minute ago, as UTC wall clock).
    const local = new Date(Date.now() - 60_000);
    const pad = (n: number) => String(n).padStart(2, '0');
    const utcWall = `${local.getUTCFullYear()}-${pad(local.getUTCMonth() + 1)}-${pad(local.getUTCDate())}T${pad(local.getUTCHours())}:${pad(local.getUTCMinutes())}:00`;
    nurseSpy.getMedicationMAR.and.returnValue(of([{ ...sampleTask, dueTime: utcWall }]));

    fixture.detectChanges();

    const text =
      root().querySelector(`[data-testid="emar-task-${sampleTask.id}"]`)?.textContent ?? '';
    expect(text).toContain(`${pad(local.getHours())}:${pad(local.getMinutes())}`);
  });

  it('QA D949: an overdue dose of another day shows its date, not the time alone', () => {
    nurseSpy.getMedicationMAR.and.returnValue(
      of([{ ...sampleTask, dueTime: '2026-04-30T08:00:00' }]),
    );
    fixture.detectChanges();
    const text =
      root().querySelector(`[data-testid="emar-task-${sampleTask.id}"]`)?.textContent ?? '';
    expect(text).toContain(formatDate(new Date(Date.UTC(2026, 3, 30, 8)), 'short', 'en-US'));
  });

  describe('QA D799: the beta-lactam cross-reactivity advisory reaches the nurse', () => {
    const advisory = {
      summary: 'Réaction croisée possible : Ceftriaxone et l’allergie documentée à Pénicilline',
      detail: 'Ceftriaxone est une bêta-lactamine…',
      indicator: 'warning' as const,
      source: { label: 'HMS allergy check' },
      uuid: 'card-1',
    };
    const cefTask: NurseMedicationTask = {
      ...sampleTask,
      id: 'task-cef',
      medication: 'Ceftriaxone',
      dose: '1 g',
      route: 'IV',
      cdsAdvisories: [advisory],
    };

    it('marks the dose in the queue and shows the card before the scan steps', () => {
      nurseSpy.getMedicationMAR.and.returnValue(of([cefTask, sampleTask]));
      fixture.detectChanges();

      expect(root().querySelector('[data-testid="emar-advisory-chip-task-cef"]')).not.toBeNull();
      expect(
        root().querySelector(`[data-testid="emar-advisory-chip-${sampleTask.id}"]`),
      ).toBeNull();

      component['selectTask'](cefTask);
      fixture.detectChanges();
      const banner = root().querySelector('[data-testid="emar-allergy-advisory"]');
      expect(banner).not.toBeNull();
      expect(banner?.textContent).toContain('Ceftriaxone');
      expect(banner?.getAttribute('role')).toBe('alert');
    });

    it('shows no advisory for a dose without one (negative control)', () => {
      nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));
      fixture.detectChanges();
      component['selectTask'](sampleTask);
      fixture.detectChanges();
      expect(root().querySelector('[data-testid="emar-allergy-advisory"]')).toBeNull();
    });

    it("repeats the verify answer's advisory next to the green toast, and Record Given stays available", () => {
      nurseSpy.getMedicationMAR.and.returnValue(of([cefTask]));
      nurseSpy.verifyMedication.and.returnValue(
        of({
          marId: cefTask.id,
          outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
          failedChecks: [],
          failureReasons: {},
          allPassed: true,
          verifiedAt: '2026-10-25T08:01:00',
          cdsAdvisories: [advisory],
        } as MarVerificationResponse),
      );
      fixture.detectChanges();
      component['selectTask'](cefTask);
      component['patientScan'].set('CHW7809');
      component['medicationScan'].set('Ceftriaxone');
      component['doseScan'].set('1 g');
      component['routeScan'].set('IV');
      component['verify']();
      fixture.detectChanges();

      expect(toastSpy.success).toHaveBeenCalled();
      expect(toastSpy.info).toHaveBeenCalledOnceWith(advisory.summary);
      expect(root().querySelector('[data-testid="emar-allergy-advisory"]')).not.toBeNull();
      expect(component['needsOverride']()).toBeFalse();
    });
  });

  it('shows the error state when the MAR fetch fails', () => {
    nurseSpy.getMedicationMAR.and.returnValue(
      throwError(() => new Error('network')) as Observable<NurseMedicationTask[]>,
    );

    fixture.detectChanges();

    expect(root().querySelector('[data-testid="emar-error"]')).not.toBeNull();
  });

  it('calls verifyMedication with all four scan values and toasts success when allPassed', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));
    const response: MarVerificationResponse = {
      marId: sampleTask.id,
      outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: [],
      failureReasons: {},
      allPassed: true,
      verifiedAt: '2026-04-30T08:01:00',
    };
    nurseSpy.verifyMedication.and.returnValue(of(response));

    fixture.detectChanges();
    component['selectTask'](sampleTask);
    component['patientScan'].set('pat-uuid');
    component['medicationScan'].set('AMOX-500');
    component['doseScan'].set('500 mg');
    component['routeScan'].set('PO');
    component['verify']();

    expect(nurseSpy.verifyMedication).toHaveBeenCalledOnceWith(sampleTask.id, {
      patientScanValue: 'pat-uuid',
      medicationScanValue: 'AMOX-500',
      doseScanValue: '500 mg',
      routeScanValue: 'PO',
    });
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('QA D1025: choosing a dose prefills neither the dose nor the route the nurse must confirm', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));
    fixture.detectChanges();
    component['selectTask'](sampleTask);
    expect(component['doseScan']()).toBe('');
    expect(component['routeScan']()).toBe('');
  });

  it('QA D922: a controlled dose is given only with a witness, sent with the dose', () => {
    const morphine: NurseMedicationTask = {
      ...sampleTask,
      id: 'task-mor',
      controlledSubstance: true,
    };
    nurseSpy.getMedicationMAR.and.returnValue(of([morphine]));
    nurseSpy.administerMedication.and.returnValue(of(morphine));
    fixture.detectChanges();
    component['selectTask'](morphine);
    fixture.detectChanges();
    component['verification'].set({
      marId: morphine.id,
      outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: [],
      failureReasons: {},
      allPassed: true,
      verifiedAt: '2026-04-30T08:01:00',
    });
    expect(root().querySelector('[data-testid="witness-picker"]')).not.toBeNull();
    expect(component['canAdminister']()).toBeFalse();
    component['administer']('GIVEN');
    expect(nurseSpy.administerMedication).not.toHaveBeenCalled();

    component['witnessUserId'].set('u-witness');
    expect(component['canAdminister']()).toBeTrue();
    component['administer']('GIVEN');
    expect(nurseSpy.administerMedication).toHaveBeenCalledOnceWith(morphine.id, {
      status: 'GIVEN',
      witnessUserId: 'u-witness',
    });
  });

  it('records GIVEN with the override reason when the five-rights check failed', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));
    nurseSpy.administerMedication.and.returnValue(of(sampleTask));

    fixture.detectChanges();
    component['selectTask'](sampleTask);
    component['verification'].set({
      marId: sampleTask.id,
      outcomes: { PATIENT: true, DRUG: false, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: ['DRUG'],
      failureReasons: { DRUG: 'mismatch' },
      allPassed: false,
      verifiedAt: '2026-04-30T08:01:00',
    });
    component['overrideReason'].set('Pharmacy confirmed verbal substitution.');

    component['administer']('GIVEN');

    expect(nurseSpy.administerMedication).toHaveBeenCalledOnceWith(sampleTask.id, {
      status: 'GIVEN',
      overrideReason: 'Pharmacy confirmed verbal substitution.',
    });
  });

  it('QA D1159: "Suspendre" and "Refus du patient" need a reason, sent as the note', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));
    nurseSpy.administerMedication.and.returnValue(of(sampleTask));
    fixture.detectChanges();
    component['selectTask'](sampleTask);
    fixture.detectChanges();
    const held = root().querySelector<HTMLButtonElement>('[data-testid="emar-administer-held"]')!;
    held.click();
    fixture.detectChanges();
    expect(nurseSpy.administerMedication).not.toHaveBeenCalled();
    expect(root().querySelector('#emar-hold-reason')?.getAttribute('aria-invalid')).toBe('true');

    const reason = root().querySelector<HTMLTextAreaElement>('#emar-hold-reason')!;
    reason.value = 'Patiente à jeun pour le bloc';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    held.click();
    expect(nurseSpy.administerMedication).toHaveBeenCalledOnceWith(sampleTask.id, {
      status: 'HELD',
      note: 'Patiente à jeun pour le bloc',
    });
  });

  it('QA D233: a refused administration shows the server reason', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));
    nurseSpy.administerMedication.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Les cinq vérifications ont échoué : motif de dérogation requis.' },
          }),
      ) as Observable<NurseMedicationTask>,
    );

    fixture.detectChanges();
    component['selectTask'](sampleTask);
    // QA D1159: a refusal is recorded with its reason.
    component['holdRefuseReason'].set('Nausées');
    component['administer']('REFUSED');

    expect(toastSpy.error).toHaveBeenCalledOnceWith(
      'Les cinq vérifications ont échoué : motif de dérogation requis.',
    );
  });

  // QA D1210 (round 23, portal half): the server refuses a dose given before
  // the minimum gap since the last one (nurse.mar.doseTooSoon on the field
  // earlyDoseReason) unless the nurse says why. The eMAR showed the refusal as
  // a toast and offered no way to give the dose early: a dead end.
  const tooSoon = () =>
    throwError(
      () =>
        new HttpErrorResponse({
          status: 400,
          error: {
            message:
              'Dose trop rapprochée : dernière dose le 01/11 10:00, prochaine au plus tôt 13:00 (toutes les 4 h).',
            field: 'earlyDoseReason',
            fieldErrors: {
              earlyDoseReason:
                'Dose trop rapprochée : dernière dose le 01/11 10:00, prochaine au plus tôt 13:00 (toutes les 4 h).',
            },
          },
        }),
    ) as Observable<NurseMedicationTask>;

  it('QA D1210: a dose refused as too soon shows why and is given early with the reason (witness kept)', () => {
    const morphine: NurseMedicationTask = {
      ...sampleTask,
      id: 'task-mor',
      controlledSubstance: true,
    };
    nurseSpy.getMedicationMAR.and.returnValue(of([morphine]));
    nurseSpy.administerMedication.and.returnValues(tooSoon(), of(morphine));
    fixture.detectChanges();
    component['selectTask'](morphine);
    component['verification'].set({
      marId: morphine.id,
      outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: [],
      failureReasons: {},
      allPassed: true,
      verifiedAt: '2026-04-30T08:01:00',
    });
    component['witnessUserId'].set('u-witness');
    fixture.detectChanges();
    // The panel's button sits below the test window's fold; the dialog it opens is a fixed overlay.
    root().querySelector<HTMLButtonElement>('[data-testid="emar-administer-given"]')!.click();
    fixture.detectChanges();

    const dialog = reasonDialog(fixture)!;
    expect(dialog).withContext('the reason is asked for').not.toBeNull();
    expect(dialog.querySelector('[data-testid="early-dose-why"]')?.textContent).toContain(
      'prochaine au plus tôt 13:00',
    );
    expect(toastSpy.error).not.toHaveBeenCalled();
    const field = dialog.querySelector<HTMLTextAreaElement>(
      '[data-testid="reason-dialog-reason"]',
    )!;
    mouseClick(field);
    field.value = 'Douleur EVA 9/10, avis du médecin de garde';
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    mouseClick(dialog.querySelector<HTMLElement>('[data-testid="reason-dialog-confirm"]')!);
    fixture.detectChanges();

    expect(nurseSpy.administerMedication).toHaveBeenCalledTimes(2);
    expect(nurseSpy.administerMedication.calls.mostRecent().args).toEqual([
      morphine.id,
      {
        status: 'GIVEN',
        witnessUserId: 'u-witness',
        earlyDoseReason: 'Douleur EVA 9/10, avis du médecin de garde',
      },
    ]);
    expect(reasonDialog(fixture)).toBeNull();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('QA D1210: "Retour" on the early-dose reason records nothing', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));
    nurseSpy.administerMedication.and.returnValue(tooSoon());
    fixture.detectChanges();
    component['selectTask'](sampleTask);
    component['holdRefuseReason'].set('');
    component['verification'].set({
      marId: sampleTask.id,
      outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: [],
      failureReasons: {},
      allPassed: true,
      verifiedAt: '2026-04-30T08:01:00',
    });
    component['administer']('GIVEN');
    const dialog = reasonDialog(fixture)!;
    mouseClick(dialog.querySelector<HTMLElement>('[data-testid="reason-dialog-cancel"]')!);
    fixture.detectChanges();
    expect(reasonDialog(fixture)).toBeNull();
    expect(nurseSpy.administerMedication).toHaveBeenCalledTimes(1);
  });

  it('does not call administer when GIVEN is requested without verification or override', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));

    fixture.detectChanges();
    component['selectTask'](sampleTask);
    // No verification ran, no override reason — administer must be a no-op.
    component['administer']('GIVEN');

    expect(nurseSpy.administerMedication).not.toHaveBeenCalled();
  });

  it('drops a late verify response when the nurse switches to a different task mid-flight', () => {
    const otherTask: NurseMedicationTask = { ...sampleTask, id: 'task-2' };
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask, otherTask]));

    // First verify call returns a Subject we can resolve later.
    const verifySubject = new Subject<MarVerificationResponse>();
    nurseSpy.verifyMedication.and.returnValue(verifySubject.asObservable());

    fixture.detectChanges();
    component['selectTask'](sampleTask);
    component['patientScan'].set('pat-uuid');
    component['medicationScan'].set('AMOX-500');
    component['doseScan'].set('500 mg');
    component['routeScan'].set('PO');
    component['verify']();

    // Switch to a different MAR row before the response arrives.
    component['selectTask'](otherTask);

    // Late response for the original task fires.
    verifySubject.next({
      marId: sampleTask.id,
      outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: [],
      failureReasons: {},
      allPassed: true,
      verifiedAt: '2026-04-30T08:01:00',
    });

    // The active task is now task-2, so the stale response must be ignored.
    expect(component['verification']()).toBeNull();
  });

  it('clears a prior verification result when any scan/dose/route input changes', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));

    fixture.detectChanges();
    component['selectTask'](sampleTask);
    component['verification'].set({
      marId: sampleTask.id,
      outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: [],
      failureReasons: {},
      allPassed: true,
      verifiedAt: '2026-04-30T08:01:00',
    });
    component['overrideReason'].set('stale reason');

    component['onDoseInput']('250 mg');

    expect(component['verification']()).toBeNull();
    expect(component['overrideReason']()).toBe('');
  });

  /* ── Pharmacist verification gate (Tier 2 item 33) ─────────────────── */

  /** In scope for the gate, and no pharmacist has verified it. */
  const blockedTask: NurseMedicationTask = {
    ...sampleTask,
    id: 'task-blocked',
    medication: 'Morphine',
    requiresPharmacistVerification: true,
    pharmacistVerified: false,
  };

  const allRightsPassed: MarVerificationResponse = {
    marId: blockedTask.id,
    outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
    failedChecks: [],
    failureReasons: {},
    allPassed: true,
    verifiedAt: '2026-04-30T08:01:00',
  };

  it('refuses GIVEN while pharmacist verification is outstanding, even with all five rights passed', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([blockedTask]));
    nurseSpy.administerMedication.and.returnValue(of(blockedTask));

    fixture.detectChanges();
    component['selectTask'](blockedTask);
    component['verification'].set(allRightsPassed);

    component['administer']('GIVEN');

    // The five rights are about this dose at this bedside; this gate is about
    // whether anyone with a pharmacy qualification has read the prescription.
    // Passing the first does not answer the second.
    expect(nurseSpy.administerMedication).not.toHaveBeenCalled();
  });

  it('offers no override for the pharmacist gate — the server refuses GIVEN regardless', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([blockedTask]));
    nurseSpy.administerMedication.and.returnValue(of(blockedTask));

    fixture.detectChanges();
    component['selectTask'](blockedTask);
    component['verification'].set(allRightsPassed);
    // An override reason is the escape hatch for a FAILED five-rights check.
    // It must not double as one for this gate, or the UI would manufacture a
    // reason for a refusal that is going to happen anyway.
    component['overrideReason'].set('Ward pharmacist unreachable, giving anyway.');

    component['administer']('GIVEN');

    expect(nurseSpy.administerMedication).not.toHaveBeenCalled();
  });

  it('still records HELD and REFUSED — they are facts about the patient, not permissions', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([blockedTask]));
    nurseSpy.administerMedication.and.returnValue(of(blockedTask));

    fixture.detectChanges();
    component['selectTask'](blockedTask);
    // QA D1159: with its reason, as every hold is recorded.
    component['holdRefuseReason'].set('En attente de la pharmacie');

    component['administer']('HELD');

    expect(nurseSpy.administerMedication).toHaveBeenCalledWith(blockedTask.id, {
      status: 'HELD',
      note: 'En attente de la pharmacie',
    });
  });

  it('states the block before the scan steps, and marks the row in the queue', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([blockedTask]));

    fixture.detectChanges();

    // Visible in the queue so a nurse planning a round sees it before walking
    // to the bed, not after scanning.
    expect(
      root().querySelector(`[data-testid="emar-pharm-chip-${blockedTask.id}"]`),
    ).not.toBeNull();

    component['selectTask'](blockedTask);
    fixture.detectChanges();

    expect(root().querySelector('[data-testid="emar-pharmacist-blocked"]')).not.toBeNull();
  });

  it('does not block a verified prescription, nor one out of scope for the gate', () => {
    const verified: NurseMedicationTask = { ...blockedTask, pharmacistVerified: true };
    nurseSpy.getMedicationMAR.and.returnValue(of([verified, sampleTask]));
    nurseSpy.administerMedication.and.returnValue(of(verified));

    fixture.detectChanges();
    component['selectTask'](verified);
    component['verification'].set(allRightsPassed);

    component['administer']('GIVEN');

    expect(nurseSpy.administerMedication).toHaveBeenCalledWith(verified.id, { status: 'GIVEN' });
    expect(root().querySelector(`[data-testid="emar-pharm-chip-${verified.id}"]`)).toBeNull();
    // sampleTask carries neither flag — a pre-V139 row must administer as before.
    expect(root().querySelector(`[data-testid="emar-pharm-chip-${sampleTask.id}"]`)).toBeNull();
  });

  it('does not invalidate verification when an input handler is called with the same value', () => {
    nurseSpy.getMedicationMAR.and.returnValue(of([sampleTask]));

    fixture.detectChanges();
    component['selectTask'](sampleTask);
    component['doseScan'].set('500 mg');
    const verified: MarVerificationResponse = {
      marId: sampleTask.id,
      outcomes: { PATIENT: true, DRUG: true, DOSE: true, ROUTE: true, TIME: true },
      failedChecks: [],
      failureReasons: {},
      allPassed: true,
      verifiedAt: '2026-04-30T08:01:00',
    };
    component['verification'].set(verified);

    // Same value → no-op, must not wipe the verification.
    component['onDoseInput']('500 mg');

    expect(component['verification']()).toBe(verified);
  });
});
