import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PayerSharesListComponent } from './payer-shares-list.component';
import { BillingService, OpenPayerShare } from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

/** QA D2706 (r70): « Parts assureurs », the claims reviewer's list of the hospital's insurer shares. */
describe('PayerSharesListComponent (QA D2706)', () => {
  let fixture: ComponentFixture<PayerSharesListComponent>;
  let billing: jasmine.SpyObj<BillingService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const open: OpenPayerShare = {
    id: 'share-1',
    invoiceId: 'inv-7',
    invoiceNumber: 'TWP-2026-000007',
    invoiceDate: '2026-11-22',
    patientName: 'Mariam R70 Démo-SawadogoT1',
    patientMrn: 'TWP8739',
    payerName: 'Mutuelle Test A',
    planName: 'Formule Test Essentielle',
    amount: 8500,
    status: 'OPEN',
  };

  function render(roles: string[], rows: OpenPayerShare[] = [open]): void {
    billing = jasmine.createSpyObj<BillingService>('BillingService', [
      'listHospitalPayerShares',
      'decidePayerShare',
    ]);
    billing.listHospitalPayerShares.and.returnValue(of(rows));
    billing.decidePayerShare.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [PayerSharesListComponent, TranslateModule.forRoot()],
      providers: [
        { provide: BillingService, useValue: billing },
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
      ],
    });
    fixture = TestBed.createComponent(PayerSharesListComponent);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
  }

  afterEach(() => {
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  it('lists the open and claimed shares with their invoice, patient and payer', () => {
    render(['ROLE_CLAIMS_REVIEWER']);

    expect(billing.listHospitalPayerShares).toHaveBeenCalledWith(['OPEN', 'CLAIMED']);
    const card = host().querySelector('[data-testid="payer-share-share-1"]')?.textContent ?? '';
    expect(card).toContain('Mutuelle Test A');
    expect(card).toContain('TWP-2026-000007');
    expect(card).toContain('TWP8739');
  });

  it('the claims reviewer marks a share claimed and paid', () => {
    render(['ROLE_CLAIMS_REVIEWER']);

    host().querySelector<HTMLButtonElement>('[data-testid="payer-share-claim-share-1"]')!.click();
    expect(billing.decidePayerShare).toHaveBeenCalledWith('inv-7', 'share-1', 'claim', undefined);
  });

  it('a refusal needs the payer reason: the empty submit is refused on the field and nothing is sent', () => {
    render(['ROLE_CLAIMS_REVIEWER']);
    host().querySelector<HTMLButtonElement>('[data-testid="payer-share-reject-share-1"]')!.click();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="payer-share-reject-confirm"]',
      sent: billing.decidePayerShare,
    });
  });

  it('the super-admin reads only: no decision button', () => {
    render(['ROLE_SUPER_ADMIN']);

    expect(host().querySelector('[data-testid="payer-share-paid-share-1"]')).toBeNull();
  });

  it('shows the paid and refused shares on demand', () => {
    render(['ROLE_ACCOUNTANT']);
    host().querySelector<HTMLButtonElement>('[data-testid="payer-shares-view-closed"]')!.click();

    expect(billing.listHospitalPayerShares).toHaveBeenCalledWith(['PAID', 'REJECTED']);
  });
});
