import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';

import { EmarComponent } from './emar.component';
import {
  MarVerificationResponse,
  NurseMedicationTask,
  NurseTaskService,
} from '../../services/nurse-task.service';
import { ToastService } from '../../core/toast.service';

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
    ]);
    toastSpy = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [EmarComponent],
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
