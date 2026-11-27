import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ClaimBatchesComponent, previousMonth } from './claim-batches.component';
import { ClaimBatch, InsurerClaimsService } from '../../services/insurer-claims.service';
import { PayerCatalogueService } from '../../services/payer-catalogue.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

/** Insurer claims PR 2 (QA D3983): « Bordereaux », the list and the draft form. */
describe('ClaimBatchesComponent (QA D3983)', () => {
  let fixture: ComponentFixture<ClaimBatchesComponent>;
  let claims: jasmine.SpyObj<InsurerClaimsService>;
  let toast: jasmine.SpyObj<ToastService>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const sent: ClaimBatch = {
    id: 'batch-1',
    number: 'BORD-2026-0001',
    payerId: 'payer-1',
    payerName: 'Mutuelle Test A',
    status: 'PARTIALLY_SETTLED',
    periodFrom: '2026-11-01',
    periodTo: '2026-11-30',
    submittedOn: '2026-12-02',
    lineCount: 3,
    claimedTotal: 13600,
    paidTotal: 9000,
    rebilledTotal: 0,
    writtenOffTotal: 0,
    writeOffPendingTotal: 0,
    outstandingTotal: 4600,
  };

  function render(roles: string[], rows: ClaimBatch[] = [sent]): void {
    claims = jasmine.createSpyObj<InsurerClaimsService>('InsurerClaimsService', [
      'listBatches',
      'createBatch',
    ]);
    claims.listBatches.and.returnValue(
      of({ content: rows, page: 0, size: 25, totalElements: rows.length, totalPages: 1 }),
    );
    claims.createBatch.and.returnValue(
      of({ ...sent, id: 'draft-9', status: 'DRAFT', number: null }),
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    TestBed.configureTestingModule({
      imports: [ClaimBatchesComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: InsurerClaimsService, useValue: claims },
        {
          provide: PayerCatalogueService,
          useValue: { list: () => of([{ id: 'payer-1', name: 'Mutuelle Test A', plans: [] }]) },
        },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        { provide: ToastService, useValue: toast },
      ],
    });
    fixture = TestBed.createComponent(ClaimBatchesComponent);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
  }

  afterEach(() => {
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  it('the usual period is the whole month before today', () => {
    expect(previousMonth(new Date(2026, 10, 27))).toEqual({ from: '2026-10-01', to: '2026-10-31' });
    expect(previousMonth(new Date(2027, 0, 5))).toEqual({ from: '2026-12-01', to: '2026-12-31' });
  });

  it('lists the bordereaux in progress with what was claimed, paid and what is outstanding', () => {
    render(['ROLE_CLAIMS_REVIEWER']);

    expect(claims.listBatches).toHaveBeenCalledWith(
      jasmine.objectContaining({ statuses: ['DRAFT', 'SUBMITTED', 'PARTIALLY_SETTLED'], page: 0 }),
    );
    const card = host().querySelector('[data-testid="claim-batch-batch-1"]')!;
    expect(card.textContent).toContain('BORD-2026-0001');
    expect(card.textContent).toMatch(/13\s?600/);
    expect(card.textContent).toMatch(/4\s?600/);
    expect(
      host().querySelector('[data-testid="claim-batch-open-batch-1"]')!.getAttribute('href'),
    ).toBe('/billing/bordereaux/batch-1');
  });

  it('a draft is asked for one payer and one period, with a key, then opened', () => {
    render(['ROLE_BILLING_SPECIALIST']);
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    fixture.componentInstance.payerId.set('payer-1');
    fixture.componentInstance.periodFrom.set('2026-11-01');
    fixture.componentInstance.periodTo.set('2026-11-30');

    fixture.componentInstance.create();

    const body = claims.createBatch.calls.mostRecent().args[0];
    expect(body).toEqual(
      jasmine.objectContaining({
        payerId: 'payer-1',
        periodFrom: '2026-11-01',
        periodTo: '2026-11-30',
      }),
    );
    expect(body.clientRequestId).toBeTruthy();
    expect(navigate).toHaveBeenCalledWith(['/billing/bordereaux', 'draft-9']);
  });

  it('without a payer, or with a period that ends before it starts, nothing is sent', () => {
    render(['ROLE_ACCOUNTANT']);

    fixture.componentInstance.create();
    expect(claims.createBatch).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();

    fixture.componentInstance.payerId.set('payer-1');
    fixture.componentInstance.periodFrom.set('2026-11-30');
    fixture.componentInstance.periodTo.set('2026-11-01');
    fixture.componentInstance.create();
    expect(claims.createBatch).not.toHaveBeenCalled();
  });

  it('the draft form pressed empty marks the payer and sends nothing', async () => {
    render(['ROLE_CLAIMS_REVIEWER']);
    // ngModel writes its value a tick after the first render: the prefilled fields must show it first.
    await fixture.whenStable();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="claim-batch-create"]',
      notMarked: ['claim-batch-from', 'claim-batch-to'],
      sent: claims.createBatch,
    });
  });

  it('the super-admin reads only: no draft form', () => {
    render(['ROLE_SUPER_ADMIN']);

    expect(host().querySelector('[data-testid="claim-batch-new"]')).toBeNull();
    expect(host().querySelector('[data-testid="claim-batches-list"]')).not.toBeNull();
  });

  it('the closed view asks for the settled and cancelled bordereaux from the first page', () => {
    render(['ROLE_ACCOUNTANT']);
    host().querySelector<HTMLButtonElement>('[data-testid="claim-batches-view-closed"]')!.click();

    expect(claims.listBatches.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ statuses: ['SETTLED', 'CANCELLED'], page: 0 }),
    );
  });
});
