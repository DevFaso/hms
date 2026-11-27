import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MedicationCatalogApprovalsComponent } from './medication-catalog-approvals';
import {
  MedicationCatalogItemResponse,
  MedicationCatalogVersion,
  PharmacyService,
} from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { answerReasonDialog, reasonDialog } from '../shared/reason-dialog/reason-dialog.testing';

const strengthChange: MedicationCatalogVersion = {
  id: 'v-1',
  hospitalId: 'h-1',
  catalogItemId: 'm-1',
  kind: 'FORMULARY',
  changeType: 'UPDATE',
  versionNumber: 2,
  status: 'PENDING_APPROVAL',
  itemLabel: 'Paracétamol 1000 mg (TABLET)',
  formulary: { nameFr: 'Paracétamol', strength: '1000', strengthUnit: 'mg', controlled: false },
  previousFormulary: {
    nameFr: 'Paracétamol',
    strength: '500',
    strengthUnit: 'mg',
    controlled: false,
  },
  changedFields: ['strength'],
  changeReason: 'Nouveau dosage référencé',
  proposedByName: 'Awa Traoré',
  proposedAt: '2026-11-26T09:00:00',
  callerMayApprove: true,
  callerMayReject: true,
  ownProposal: false,
};

const page = (content: MedicationCatalogVersion[]) => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  size: 50,
  number: 0,
});

/**
 * Go-live D2 (V385): the approval queue and the history of the medication catalogue. A version
 * shows what is proposed beside what was in force, with who, when and why; approve and refuse
 * appear only where the server says this user may, and a refusal asks its reason.
 */
// The keyed labels (status, kind, field) go through keyLabel, which prints the value itself when no
// translation is loaded, as here: 'PENDING_APPROVAL', 'strength'.
describe('MedicationCatalogApprovalsComponent (go-live D2)', () => {
  let svc: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  let fixture: ComponentFixture<MedicationCatalogApprovalsComponent>;
  let el: HTMLElement;

  function render(
    versions: MedicationCatalogVersion[],
    entry: MedicationCatalogItemResponse | null = null,
  ): void {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listCatalogVersions',
      'catalogVersionHistory',
      'approveCatalogVersion',
      'rejectCatalogVersion',
      'withdrawCatalogVersion',
    ]);
    svc.listCatalogVersions.and.returnValue(of(page(versions)));
    svc.catalogVersionHistory.and.returnValue(of(versions));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    TestBed.configureTestingModule({
      imports: [MedicationCatalogApprovalsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    fixture = TestBed.createComponent(MedicationCatalogApprovalsComponent);
    fixture.componentRef.setInput('entry', entry);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const text = (selector: string) => el.querySelector(selector)?.textContent?.trim() ?? '';

  it('reads the queue of versions awaiting approval, and shows the change, its author and its reason', () => {
    render([strengthChange]);

    expect(svc.listCatalogVersions).toHaveBeenCalledOnceWith('PENDING_APPROVAL', 0, 200);
    expect(text('.version-title')).toBe('Paracétamol 1000 mg (TABLET)');
    expect(text('[data-test="approval-status"]')).toBe('PENDING_APPROVAL');
    expect(text('.version-who')).toContain('Awa Traoré');
    expect(text('.version-who')).toContain('Nouveau dosage référencé');
    const changed = el.querySelector('tr.changed')!;
    expect(changed.textContent).toContain('strength');
    expect(changed.textContent).toContain('500');
    expect(changed.textContent).toContain('1000');
    // Unchanged fields are shown too, unmarked: the approver reads the whole entry.
    expect(el.querySelectorAll('[data-test="approval-line"]').length).toBeGreaterThan(1);
    expect(el.querySelectorAll('tr.changed').length).toBe(1);
  });

  it('approves a version, reads the queue again and tells its host', () => {
    render([strengthChange]);
    svc.approveCatalogVersion.and.returnValue(of({ ...strengthChange, status: 'APPROVED' }));
    const decided = jasmine.createSpy('decided');
    fixture.componentInstance.decided.subscribe(decided);

    el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.click();

    expect(svc.approveCatalogVersion).toHaveBeenCalledOnceWith('v-1');
    expect(toast.success).toHaveBeenCalledOnceWith('PHARMACY.CATALOG_APPROVAL.APPROVED_TOAST');
    expect(svc.listCatalogVersions).toHaveBeenCalledTimes(2);
    expect(decided).toHaveBeenCalledTimes(1);
  });

  it('offers the author no approval of their own proposal, only to withdraw it, and says why', () => {
    render([{ ...strengthChange, ownProposal: true, callerMayApprove: false }]);

    expect(el.querySelector('[data-test="approval-approve"]')).toBeNull();
    expect(el.querySelector('[data-test="approval-withdraw"]')).not.toBeNull();
    expect(text('[data-test="approval-own-note"]')).toBe(
      'PHARMACY.CATALOG_APPROVAL.OWN_PROPOSAL_NOTE',
    );
  });

  it('offers a reader with no deciding role neither button, and names who decides', () => {
    render([{ ...strengthChange, callerMayApprove: false, callerMayReject: false }]);

    expect(el.querySelector('[data-test="approval-approve"]')).toBeNull();
    expect(el.querySelector('[data-test="approval-refuse"]')).toBeNull();
    expect(el.querySelector('[data-test="approval-withdraw"]')).toBeNull();
    expect(text('[data-test="approval-role-note"]')).toBe('FORMULARY');
  });

  it('refuses a version only with a reason, asked by the shared dialog', () => {
    render([strengthChange]);
    svc.rejectCatalogVersion.and.returnValue(of({ ...strengthChange, status: 'REJECTED' }));
    el.querySelector<HTMLButtonElement>('[data-test="approval-refuse"]')!.click();
    const dialog = reasonDialog(fixture)!;
    expect(dialog.textContent).toContain('Paracétamol 1000 mg (TABLET)');

    // A blank reason sends nothing.
    dialog.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
    fixture.detectChanges();
    expect(svc.rejectCatalogVersion).not.toHaveBeenCalled();

    answerReasonDialog(fixture, '  Dosage non référencé  ');
    expect(svc.rejectCatalogVersion).toHaveBeenCalledOnceWith('v-1', 'Dosage non référencé');
    expect(toast.success).toHaveBeenCalledOnceWith('PHARMACY.CATALOG_APPROVAL.REFUSED_TOAST');
    expect(reasonDialog(fixture)).toBeNull();
  });

  it('withdraws the author’s own proposal only after the confirmation', async () => {
    render([{ ...strengthChange, ownProposal: true, callerMayApprove: false }]);
    svc.withdrawCatalogVersion.and.returnValue(of({ ...strengthChange, status: 'WITHDRAWN' }));

    confirm.ask.and.resolveTo(false);
    await fixture.componentInstance.withdraw(strengthChange);
    expect(svc.withdrawCatalogVersion).not.toHaveBeenCalled();

    confirm.ask.and.resolveTo(true);
    await fixture.componentInstance.withdraw(strengthChange);
    expect(svc.withdrawCatalogVersion).toHaveBeenCalledOnceWith('v-1');
    expect(toast.success).toHaveBeenCalledOnceWith('PHARMACY.CATALOG_APPROVAL.WITHDRAWN_TOAST');
  });

  it("says the server's reason when an approval is refused, and keeps the version in the queue", () => {
    render([strengthChange]);
    svc.approveCatalogVersion.and.returnValue(
      throwError(() => ({ error: { message: 'Une version est validée par une autre personne' } })),
    );

    el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.click();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.success).not.toHaveBeenCalled();
    expect(el.querySelectorAll('[data-test="approval-version"]').length).toBe(1);
    expect(
      el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.disabled,
    ).toBeFalse();
  });

  it('shows the history of every version with who decided and why, and no action on a decided one', () => {
    render([strengthChange]);
    svc.listCatalogVersions.and.returnValue(
      of(
        page([
          {
            ...strengthChange,
            status: 'REJECTED',
            decidedByName: 'Issa Sawadogo',
            decidedAt: '2026-11-26T10:00:00',
            decisionReason: 'Dosage non référencé',
          },
        ]),
      ),
    );

    el.querySelector<HTMLButtonElement>('[data-test="approvals-view-history"]')!.click();
    fixture.detectChanges();

    expect(svc.listCatalogVersions).toHaveBeenCalledWith(null, 0, 200);
    expect(text('[data-test="approval-status"]')).toBe('REJECTED');
    expect(text('.version-who')).toContain('Issa Sawadogo');
    expect(text('[data-test="approval-decision-reason"]')).toBe('Dosage non référencé');
    expect(el.querySelector('[data-test="approval-approve"]')).toBeNull();
    expect(el.querySelector('[data-test="approval-refuse"]')).toBeNull();
  });

  it('reads one entry’s versions when given an entry, with no queue tabs', () => {
    render([{ ...strengthChange, status: 'APPROVED' }], {
      id: 'm-1',
      nameFr: 'Paracétamol',
    } as MedicationCatalogItemResponse);

    expect(svc.catalogVersionHistory).toHaveBeenCalledOnceWith('m-1');
    expect(svc.listCatalogVersions).not.toHaveBeenCalled();
    expect(el.querySelector('[data-test="approvals-view-pending"]')).toBeNull();
    expect(el.querySelectorAll('[data-test="approval-version"]').length).toBe(1);
  });

  it('shows dose limits with their weight bands, and a failed read inline with a retry', () => {
    render([
      {
        ...strengthChange,
        kind: 'DOSE_LIMIT',
        changeType: 'UPDATE',
        formulary: null,
        previousFormulary: null,
        doseLimits: {
          maxDoseMgPerKg: 15,
          weightBands: [
            {
              minWeightKg: 5,
              maxWeightKg: 15,
              dosePerAdministration: 1,
              doseUnit: 'cp',
              maxDosesPerDay: 2,
            },
          ],
        },
        previousDoseLimits: { maxDoseMgPerKg: 10, weightBands: [] },
        changedFields: ['maxDoseMgPerKg', 'weightBands'],
      } as MedicationCatalogVersion,
    ]);
    expect(el.querySelectorAll('tr.changed').length).toBe(2);
    expect(el.querySelector('.version-table')!.textContent).toContain('maxDoseMgPerKg');

    svc.listCatalogVersions.and.returnValues(
      throwError(() => new Error('offline')),
      of(page([])),
    );
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(el.querySelectorAll('[role="alert"]').length).toBe(1);
    el.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.querySelector('[data-test="approvals-empty"]')).not.toBeNull();
  });
});
