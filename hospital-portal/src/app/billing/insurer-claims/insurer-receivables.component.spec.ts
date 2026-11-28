import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { InsurerReceivablesComponent } from './insurer-receivables.component';
import {
  AgeingRow,
  ClaimWriteOff,
  InsurerClaimsService,
  ReconciliationRow,
} from '../../services/insurer-claims.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

/** Insurer claims PR 4 (QA D3983): « Créances assureurs »: ageing, reconciliation, write-offs to approve. */
describe('InsurerReceivablesComponent (QA D3983)', () => {
  let fixture: ComponentFixture<InsurerReceivablesComponent>;
  let claims: jasmine.SpyObj<InsurerClaimsService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const aged: AgeingRow = {
    payerId: 'payer-1',
    payerName: 'Mutuelle Test A',
    notClaimed: 4000,
    days0To30: 2800,
    days31To60: 5000,
    days61To90: 0,
    over90Days: 1200,
    outstanding: 9000,
    writeOffPending: 1200,
    unallocated: 3000,
  };
  const reconciled: ReconciliationRow = {
    payerId: 'payer-1',
    payerName: 'Mutuelle Test A',
    batchCount: 2,
    claimed: 25400,
    paid: 12000,
    rebilled: 2800,
    writtenOff: 1600,
    writeOffPending: 1200,
    outstanding: 9000,
    received: 15000,
    unallocated: 3000,
  };
  const asked: ClaimWriteOff = {
    id: 'wo-1',
    claimLineId: 'line-1',
    batchId: 'batch-1',
    batchNumber: 'BORD-2026-0001',
    payerName: 'Mutuelle Test A',
    invoiceNumber: 'F-0007',
    patientName: 'Awa Sawadogo',
    amount: 1200,
    reasonCode: 'COMMERCIAL_WAIVER',
    reason: 'Accord amiable',
    supportingDocument: 'Lettre du 12/11',
    status: 'REQUESTED',
    requestedByName: 'Issa Kaboré',
    requestedByMe: false,
  };

  function render(roles: string[], writeOffs: ClaimWriteOff[] = [asked]): void {
    claims = jasmine.createSpyObj<InsurerClaimsService>('InsurerClaimsService', [
      'ageing',
      'reconciliation',
      'listWriteOffs',
      'approveWriteOff',
      'refuseWriteOff',
      'cancelWriteOff',
    ]);
    claims.ageing.and.returnValue(of({ asOf: '2026-11-27', rows: [aged], total: aged }));
    claims.reconciliation.and.returnValue(
      of({ fromDate: null, toDate: '2026-11-27', rows: [reconciled], total: reconciled }),
    );
    claims.listWriteOffs.and.returnValue(of(writeOffs));
    claims.approveWriteOff.and.returnValue(of({ ...asked, status: 'APPROVED' }));
    claims.refuseWriteOff.and.returnValue(of({ ...asked, status: 'REFUSED' }));
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);
    TestBed.configureTestingModule({
      imports: [InsurerReceivablesComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: InsurerClaimsService, useValue: claims },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'info']),
        },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    fixture = TestBed.createComponent(InsurerReceivablesComponent);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
  }

  afterEach(() => {
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  it('shows each payer by age bucket with the total row, and the reconciliation of the period', () => {
    render(['ROLE_CLAIMS_REVIEWER']);

    const ageing = host().querySelector('[data-testid="ageing-table"]')!;
    expect(ageing.textContent).toContain('Mutuelle Test A');
    expect(host().querySelector('[data-testid="ageing-total"]')!.textContent).toMatch(/9\s?000/);
    const total = host().querySelector('[data-testid="reconciliation-total"]')!.textContent ?? '';
    expect(total).toMatch(/25\s?400/);
    expect(total).toMatch(/12\s?000/);
    // The identity the report rests on: claimed = paid + re-billed + written off + outstanding.
    expect(reconciled.claimed).toBe(
      reconciled.paid + reconciled.rebilled + reconciled.writtenOff + reconciled.outstanding,
    );
  });

  it('a period that ends before it starts is refused and nothing is asked', () => {
    render(['ROLE_ACCOUNTANT']);
    claims.reconciliation.calls.reset();
    fixture.componentInstance.fromDate.set('2026-11-30');
    fixture.componentInstance.toDate.set('2026-11-01');

    fixture.componentInstance.loadReconciliation();

    expect(claims.reconciliation).not.toHaveBeenCalled();
  });

  it('another accountant approves a write-off after a confirmation', async () => {
    render(['ROLE_ACCOUNTANT']);

    await fixture.componentInstance.approve(asked);

    expect(confirm.ask).toHaveBeenCalled();
    expect(claims.approveWriteOff).toHaveBeenCalledWith('wo-1');
  });

  it('the person who asked for a write-off cannot approve it, only withdraw it', () => {
    render(['ROLE_ACCOUNTANT'], [{ ...asked, requestedByMe: true }]);

    expect(host().querySelector('[data-testid="write-off-approve-wo-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="write-off-own-wo-1"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="write-off-withdraw-wo-1"]')).not.toBeNull();
  });

  it('the refusal of a write-off pressed empty marks its reason and sends nothing', () => {
    render(['ROLE_HOSPITAL_ADMIN']);
    host().querySelector<HTMLButtonElement>('[data-testid="write-off-refuse-wo-1"]')!.click();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="write-off-refuse-confirm"]',
      sent: claims.refuseWriteOff,
    });
  });

  it('a claims reviewer decides no write-off; a refusal needs its reason', () => {
    render(['ROLE_CLAIMS_REVIEWER']);
    expect(host().querySelector('[data-testid="write-off-approve-wo-1"]')).toBeNull();
    (fixture.nativeElement as HTMLElement).remove();
    TestBed.resetTestingModule();

    render(['ROLE_HOSPITAL_ADMIN']);
    host().querySelector<HTMLButtonElement>('[data-testid="write-off-refuse-wo-1"]')!.click();
    fixture.detectChanges();
    fixture.componentInstance.confirmRefuse(asked);
    expect(claims.refuseWriteOff).not.toHaveBeenCalled();
    fixture.componentInstance.refuseReason.set('Pièce insuffisante');
    fixture.componentInstance.confirmRefuse(asked);
    expect(claims.refuseWriteOff).toHaveBeenCalledWith('wo-1', 'Pièce insuffisante');
  });
});
