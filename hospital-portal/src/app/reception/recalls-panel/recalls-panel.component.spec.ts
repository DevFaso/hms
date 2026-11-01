import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { RecallsPanelComponent } from './recalls-panel.component';
import { SUBLINE_AWA, SUBLINE_FATIMATA } from '../../testing/patient-subline.fixtures';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { ReceptionService, RecallResponse } from '../reception.service';
import { PatientService } from '../../services/patient.service';
import { ReferralService } from '../../services/referral.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';

function recall(overrides: Partial<RecallResponse> = {}): RecallResponse {
  return {
    id: 'r1',
    hospitalId: 'h1',
    patientId: 'p1',
    patientName: 'Awa Traore',
    mrn: 'MRN-1',
    departmentId: null,
    departmentName: null,
    preferredProviderId: null,
    preferredProviderName: null,
    encounterId: null,
    recallType: 'FOLLOW_UP',
    status: 'PENDING',
    source: 'MANUAL',
    dueDate: '2026-09-15',
    reason: 'Diabetes review',
    notes: null,
    notifiedAt: null,
    linkedAppointmentId: null,
    closedAt: null,
    createdAt: '2026-08-20T09:00:00',
    createdBy: 'reception1',
    ...overrides,
  };
}

describe('RecallsPanelComponent', () => {
  let component: RecallsPanelComponent;
  let fixture: ComponentFixture<RecallsPanelComponent>;
  let mockReceptionService: {
    getRecalls: jasmine.Spy;
    createRecall: jasmine.Spy;
    closeRecall: jasmine.Spy;
    cancelRecall: jasmine.Spy;
  };
  let mockToastService: { success: jasmine.Spy; error: jasmine.Spy };

  const mockPatientService = { list: () => of([]) };
  const mockReferralService = { getDepartmentsByHospital: () => of([]) };
  const mockRoleCtx = { activeHospitalId: 'h1', effectiveHospitalIdForRequest: () => 'h1' };

  beforeEach(async () => {
    mockReceptionService = {
      getRecalls: jasmine.createSpy('getRecalls').and.returnValue(of([])),
      createRecall: jasmine.createSpy('createRecall').and.returnValue(of(recall())),
      closeRecall: jasmine
        .createSpy('closeRecall')
        .and.returnValue(of(recall({ status: 'CLOSED' }))),
      cancelRecall: jasmine
        .createSpy('cancelRecall')
        .and.returnValue(of(recall({ status: 'CANCELLED' }))),
    };
    mockToastService = {
      success: jasmine.createSpy('success'),
      error: jasmine.createSpy('error'),
    };

    await TestBed.configureTestingModule({
      imports: [RecallsPanelComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: PatientService, useValue: mockPatientService },
        { provide: ReferralService, useValue: mockReferralService },
        { provide: RoleContextService, useValue: mockRoleCtx },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RecallsPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('defaults to the PENDING filter and loads on init', () => {
    expect(component.statusFilter()).toBe('PENDING');
    expect(mockReceptionService.getRecalls).toHaveBeenCalledWith({ status: 'PENDING' });
  });

  it('passes no status for the ALL filter', () => {
    component.statusFilter.set('ALL');
    component.loadRecalls();
    expect(mockReceptionService.getRecalls).toHaveBeenCalledWith({ status: undefined });
  });

  // QA D912: two patients with the same name were told apart by nothing but a
  // birth date; each search result now carries this hospital's MRN.
  it('names each search result by MRN (QA D912)', () => {
    component.openAddForm();
    component.patientResults.set([SUBLINE_AWA, SUBLINE_FATIMATA]);
    fixture.detectChanges();
    const hints = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.autocomplete-list .mrn-hint'),
    ).map((h) => h.textContent?.trim());
    expect(hints).toEqual(['· CHW2572', '· CHW1444']);
  });

  it('refuses to create a recall without the required fields', () => {
    component.openAddForm();
    component.submitAdd();

    expect(mockReceptionService.createRecall).not.toHaveBeenCalled();
    expect(mockToastService.error).toHaveBeenCalled();
  });

  it('creates a manual recall', () => {
    component.openAddForm();
    component.selectPatient({ id: 'p1', firstName: 'Awa', lastName: 'Traore' } as any);
    component.dueDate.set('2026-10-01');
    component.reason.set('Repeat HbA1c');

    component.submitAdd();

    expect(mockReceptionService.createRecall).toHaveBeenCalledWith({
      patientId: 'p1',
      departmentId: null,
      recallType: 'FOLLOW_UP',
      dueDate: '2026-10-01',
      reason: 'Repeat HbA1c',
      notes: null,
    });
    expect(component.showAddForm()).toBe(false);
    expect(mockToastService.success).toHaveBeenCalled();
  });

  it('surfaces the backend refusal verbatim when creation fails', () => {
    mockReceptionService.createRecall.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'The patient is not registered at this hospital.' },
      })),
    );
    component.openAddForm();
    component.selectPatient({ id: 'p1', firstName: 'Awa', lastName: 'Traore' } as any);
    component.dueDate.set('2026-10-01');
    component.reason.set('Repeat HbA1c');

    component.submitAdd();

    expect(mockToastService.error).toHaveBeenCalledWith(
      'The patient is not registered at this hospital.',
    );
    expect(component.saving()).toBe(false);
  });

  it('closes and cancels open recalls', () => {
    component.closeRecall(recall());
    expect(mockReceptionService.closeRecall).toHaveBeenCalledWith('r1');

    // QA D1157: a cancel carries the reason the dialog asked for.
    component.cancelRecall(recall({ id: 'r2' }), 'Patient décédé');
    expect(mockReceptionService.cancelRecall).toHaveBeenCalledWith('r2', 'Patient décédé');
  });

  it('QA D1157: "Annuler" asks for a reason in a dialog; Escape keeps the recall and gives the keyboard back', fakeAsync(() => {
    mockReceptionService.getRecalls.and.returnValue(of([recall({ id: 'r2', status: 'PENDING' })]));
    component.loadRecalls();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
    try {
      const opener = Array.from(
        host.querySelectorAll<HTMLButtonElement>('.actions-cell .btn-ghost, td button.btn-ghost'),
      ).find((b) => b.textContent?.includes('COMMON.CANCEL'))!;
      opener.focus();
      opener.click();
      fixture.detectChanges();
      tick();
      expect(mockReceptionService.cancelRecall).not.toHaveBeenCalled();
      const dialog = host.querySelector<HTMLElement>('[data-testid="reason-dialog"]')!;
      expect(dialog.getAttribute('role')).toBe('dialog');
      expect(dialog.contains(document.activeElement)).toBeTrue();

      // No reason, no cancel.
      host.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
      fixture.detectChanges();
      expect(mockReceptionService.cancelRecall).not.toHaveBeenCalled();

      document.activeElement!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      tick();
      expect(host.querySelector('[data-testid="reason-dialog"]')).toBeNull();
      expect(document.activeElement).toBe(opener);

      opener.click();
      fixture.detectChanges();
      tick();
      const reason = host.querySelector<HTMLTextAreaElement>(
        '[data-testid="reason-dialog-reason"]',
      )!;
      reason.value = 'Rendez-vous pris ailleurs';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
      fixture.detectChanges();
      tick();
      expect(mockReceptionService.cancelRecall).toHaveBeenCalledWith(
        'r2',
        'Rendez-vous pris ailleurs',
      );
      expect(host.querySelector('[data-testid="reason-dialog"]')).toBeNull();
    } finally {
      host.remove();
    }
  }));

  it('QA D1199: "Fermer" leaves the keyboard on the list filter, never the page body', fakeAsync(() => {
    mockReceptionService.getRecalls.and.returnValue(of([recall({ id: 'r1', status: 'PENDING' })]));
    component.loadRecalls();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
    try {
      const close = Array.from(host.querySelectorAll<HTMLButtonElement>('td button')).find((b) =>
        b.textContent?.includes('COMMON.CLOSE'),
      )!;
      close.focus();
      mockReceptionService.getRecalls.and.returnValue(of([]));
      close.click();
      fixture.detectChanges();
      tick();
      expect(mockReceptionService.closeRecall).toHaveBeenCalledWith('r1');
      expect(document.activeElement).not.toBe(document.body);
      expect(document.activeElement).toBe(host.querySelector('.filter-btn.active'));
    } finally {
      host.remove();
    }
  }));

  it('guards against double submission while a call is in flight', () => {
    component.actingOnId.set('other');
    component.closeRecall(recall());
    expect(mockReceptionService.closeRecall).not.toHaveBeenCalled();
  });

  it('flags overdue open recalls only', () => {
    expect(component.isOverdue(recall({ dueDate: '2000-01-01' }))).toBe(true);
    expect(component.isOverdue(recall({ dueDate: '2999-01-01' }))).toBe(false);
    expect(component.isOverdue(recall({ dueDate: '2000-01-01', status: 'CLOSED' }))).toBe(false);
  });
});
