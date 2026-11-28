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
    // QA D4187 (r81): the dosage form through its translation key, never the raw code.
    expect(text('.version-title')).toBe('Paracétamol 1000 mg (PHARMACY.FORM_OPTION.TABLET)');
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

  it('approves a version only after the confirmation (QA D4189), reads the queue again and tells its host', async () => {
    render([strengthChange]);
    svc.approveCatalogVersion.and.returnValue(of({ ...strengthChange, status: 'APPROVED' }));
    const decided = jasmine.createSpy('decided');
    fixture.componentInstance.decided.subscribe(decided);

    // One click no longer approves: the approver is asked, and « Retour » changes nothing.
    confirm.ask.and.resolveTo(false);
    el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.click();
    await fixture.whenStable();
    expect(svc.approveCatalogVersion).not.toHaveBeenCalled();
    expect(confirm.ask.calls.mostRecent().args[0].titleKey).toBe(
      'PHARMACY.CATALOG_APPROVAL.APPROVE_CONFIRM_TITLE',
    );

    confirm.ask.and.resolveTo(true);
    el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.click();
    await fixture.whenStable();

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
    expect(dialog.textContent).toContain('Paracétamol 1000 mg (PHARMACY.FORM_OPTION.TABLET)');

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

  it("says the server's reason when an approval is refused, and keeps the version in the queue", async () => {
    render([strengthChange]);
    svc.approveCatalogVersion.and.returnValue(
      throwError(() => ({ error: { message: 'Une version est validée par une autre personne' } })),
    );
    confirm.ask.and.resolveTo(true);

    el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.success).not.toHaveBeenCalled();
    expect(el.querySelectorAll('[data-test="approval-version"]').length).toBe(1);
    expect(
      el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.disabled,
    ).toBeFalse();
  });

  it("QA D4187: writes the form and the route of a version in the reader's words, never their codes", () => {
    render([
      {
        ...strengthChange,
        formulary: { ...strengthChange.formulary, form: 'CAPSULE', route: 'ORAL' },
        previousFormulary: { ...strengthChange.previousFormulary, form: 'TABLET', route: 'ORAL' },
        changedFields: ['form'],
      },
    ]);
    const rows = Array.from(el.querySelectorAll('[data-test="approval-line"]')).map(
      (row) => row.textContent ?? '',
    );
    const form = rows.find((row) => row.includes('form'))!;
    expect(form).toContain('PHARMACY.FORM_OPTION.TABLET');
    expect(form).toContain('PHARMACY.FORM_OPTION.CAPSULE');
    expect(rows.find((row) => row.includes('route'))).toContain('PHARMACY.ROUTE_OPTION.ORAL');
  });

  it('QA D4180: shows the approver the entry that looks like the proposal, and says it again when asked', async () => {
    render([
      {
        ...strengthChange,
        similarEntries: [
          {
            catalogItemId: 'm-2',
            label: 'Paracétamol 1000 mg (TABLET)',
            form: 'TABLET',
            exact: false,
            state: 'IN_CATALOGUE',
            platform: true,
          },
        ],
      },
    ]);
    const similar = el.querySelector('[data-test="approval-similar"]')!;
    expect(similar.textContent).toContain('Paracétamol 1000 mg (PHARMACY.FORM_OPTION.TABLET)');
    expect(similar.textContent).toContain('PHARMACY.CATALOG_DUPLICATE.PLATFORM');

    confirm.ask.and.resolveTo(false);
    el.querySelector<HTMLButtonElement>('[data-test="approval-approve"]')!.click();
    await fixture.whenStable();
    const asked = confirm.ask.calls.mostRecent().args[0];
    expect(asked.messageKey).toBe('PHARMACY.CATALOG_APPROVAL.APPROVE_CONFIRM_SIMILAR');
    expect(asked.params?.['similar']).toContain('Paracétamol 1000 mg');
  });

  it('QA D4190: a withdrawal is a line of the history, with its note and no action', () => {
    render([
      {
        ...strengthChange,
        changeType: 'WITHDRAWAL',
        status: 'RECORDED',
        changedFields: ['active'],
        decidedByName: 'Awa Traoré',
        decidedAt: '2026-11-26T09:00:00',
        callerMayApprove: false,
        callerMayReject: false,
      },
    ]);
    expect(text('[data-test="approval-status"]')).toBe('RECORDED');
    expect(el.querySelector('[data-test="approval-withdrawal-note"]')).not.toBeNull();
    expect(el.querySelector('[data-test="approval-approve"]')).toBeNull();
    // QA D4189: a decided version's first column is what it replaced, not « in force ».
    expect(el.querySelector('thead')!.textContent).toContain(
      'PHARMACY.CATALOG_APPROVAL.COL_BEFORE_DECIDED',
    );
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

  it('QA D4188: the two views are the tabs of one tab bar, the one shown marked as such', () => {
    render([strengthChange]);
    const tabs = Array.from(el.querySelectorAll<HTMLButtonElement>('.tab-bar > button.tab-btn'));
    expect(tabs.map((b) => b.dataset['test'])).toEqual([
      'approvals-view-pending',
      'approvals-view-history',
    ]);
    expect(tabs.map((b) => b.classList.contains('active'))).toEqual([true, false]);
    expect(tabs[0].getAttribute('aria-pressed')).toBe('true');

    tabs[1].click();
    fixture.detectChanges();

    expect(tabs.map((b) => b.classList.contains('active'))).toEqual([false, true]);
    expect(tabs[1].getAttribute('aria-pressed')).toBe('true');
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
