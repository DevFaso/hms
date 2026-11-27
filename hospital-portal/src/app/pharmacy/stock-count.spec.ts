import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { StockCountComponent } from './stock-count';
import { PharmacyService } from '../services/pharmacy.service';
import {
  StockBalanceDrift,
  StockCountResponse,
  StockCountService,
} from '../services/stock-count.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../testing/form-refusal-behaviour';

const page = <T>(content: T[]) => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  size: 200,
  number: 0,
  last: true,
});

const count = (over: Partial<StockCountResponse> = {}): StockCountResponse => ({
  id: 'c-1',
  inventoryItemId: 'i-1',
  stockLotId: 'l-1',
  medicationName: 'Métronidazole 250 mg',
  lotNumber: 'R81-A-1',
  unit: 'comprimé',
  bookQuantity: 80,
  countedQuantity: 76,
  variance: -4,
  reason: 'Inventaire mensuel',
  recordedByName: 'Awa Traoré',
  recordedAt: '2026-11-27T05:40:00',
  status: 'PENDING',
  ownCount: false,
  controlled: false,
  ...over,
});

/**
 * QA D4183 (r81): the physical stock count had no screen. Counting, confirming by a second person, closing
 * (QA D4191) and the balance reconciliation, for the roles their endpoints admit.
 */
describe('StockCountComponent (QA D4183)', () => {
  let api: jasmine.SpyObj<StockCountService>;
  let pharmacy: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  let fixture: ComponentFixture<StockCountComponent>;
  let el: HTMLElement;

  function render(roles: string[], counts: StockCountResponse[] = []): StockCountComponent {
    api = jasmine.createSpyObj<StockCountService>('StockCountService', [
      'stores',
      'list',
      'record',
      'confirm',
      'close',
      'balanceDrift',
      'reconcileBalance',
    ]);
    api.stores.and.returnValue(of([{ pharmacyId: 'p-1', pharmacyName: 'Pharmacie principale' }]));
    api.list.and.returnValue(of(page(counts)));
    api.balanceDrift.and.returnValue(of([]));
    pharmacy = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listInventoryByPharmacy',
      'listLotsByPharmacy',
      'listWitnessCandidates',
    ]);
    pharmacy.listInventoryByPharmacy.and.returnValue(
      of({
        data: page([
          {
            id: 'i-1',
            pharmacyId: 'p-1',
            medicationName: 'Métronidazole 250 mg',
            unit: 'comprimé',
            quantityOnHand: 83,
            stockRevision: 7,
            active: true,
          },
          {
            id: 'i-2',
            pharmacyId: 'p-1',
            medicationName: 'Compresses',
            unit: 'paquet',
            quantityOnHand: 12,
            stockRevision: 2,
            active: true,
          },
        ]),
      }) as never,
    );
    pharmacy.listLotsByPharmacy.and.returnValue(
      of({
        data: page([
          {
            id: 'l-1',
            inventoryItemId: 'i-1',
            lotNumber: 'R81-A-1',
            expiryDate: '2027-06-30',
            remainingQuantity: 80,
            stockRevision: 3,
            productName: 'Métronidazole 250 mg',
            form: 'TABLET',
          },
          {
            id: 'l-2',
            inventoryItemId: 'i-1',
            lotNumber: 'R81-A-2',
            expiryDate: '2027-01-31',
            remainingQuantity: 3,
            stockRevision: 1,
            productName: 'Métronidazole 250 mg',
            form: 'TABLET',
          },
          {
            id: 'l-0',
            inventoryItemId: 'i-1',
            lotNumber: 'VIDE',
            expiryDate: '2026-12-31',
            remainingQuantity: 0,
            stockRevision: 9,
            productName: 'Métronidazole 250 mg',
          },
        ]),
      }) as never,
    );
    pharmacy.listWitnessCandidates.and.returnValue(
      of({ data: [{ userId: 'u-2', displayName: 'Issa Sawadogo' }] }) as never,
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    TestBed.configureTestingModule({
      imports: [StockCountComponent, TranslateModule.forRoot()],
      providers: [
        { provide: StockCountService, useValue: api },
        { provide: PharmacyService, useValue: pharmacy },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRoleExactly: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(StockCountComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    return fixture.componentInstance;
  }

  function startSheet(c: StockCountComponent): void {
    c.setTab('sheet');
    c.countReason = 'Inventaire mensuel';
    c.startCount();
    fixture.detectChanges();
  }

  it('lists the store’s counts with the medicine beside the lot, and offers the clerk a new count', () => {
    render(['ROLE_INVENTORY_CLERK'], [count()]);
    expect(api.list).toHaveBeenCalledWith('p-1', 0, 200);
    const row = el.querySelector('[data-testid="count-row"]')!;
    expect(row.textContent).toContain('Métronidazole 250 mg');
    expect(row.textContent).toContain('R81-A-1');
    expect(row.textContent).toContain('-4');
    expect(el.querySelector('[data-testid="count-status"]')!.textContent).toContain('PENDING');
    expect(el.querySelector('[data-testid="count-new"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="count-confirm"]')).not.toBeNull();
  });

  it('gives the accountant the lists and no write: no count, no confirmation, no closing, no reconciliation', () => {
    const c = render(['ROLE_ACCOUNTANT'], [count()]);
    expect(el.querySelector('[data-testid="count-row"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="tab-sheet"]')).toBeNull();
    expect(el.querySelector('[data-testid="count-new"]')).toBeNull();
    expect(el.querySelector('[data-testid="count-confirm"]')).toBeNull();
    expect(el.querySelector('[data-testid="count-close"]')).toBeNull();
    api.balanceDrift.and.returnValue(of([drift()]));
    c.setTab('balance');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="balance-row"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="balance-open"]')).toBeNull();
  });

  it('a count needs its purpose, then lists each lot with its medicine and book quantity; empty lots on request', () => {
    const c = render(['ROLE_PHARMACIST']);
    c.setTab('sheet');
    c.startCount();
    expect(toast.error).toHaveBeenCalledOnceWith('STOCK_COUNT.SHEET.REASON_REQUIRED');
    expect(pharmacy.listLotsByPharmacy).not.toHaveBeenCalled();

    startSheet(c);
    const lines = Array.from(el.querySelectorAll('[data-testid="sheet-line"]')).map(
      (line) => line.textContent ?? '',
    );
    // Two lots with stock, and the item that keeps no lot; the empty lot is hidden until asked for.
    expect(lines.length).toBe(3);
    expect(lines.every((line) => /Métronidazole 250 mg|Compresses/.test(line))).toBeTrue();
    expect(lines.join(' ')).toContain('R81-A-1');
    expect(lines.join(' ')).toContain('80 comprimés');
    expect(lines.join(' ')).toContain('STOCK_COUNT.NO_LOT');
    expect(c.hiddenEmptyLots()).toBe(1);
    c.showEmptyLots = true;
    expect(c.visibleSheet().length).toBe(4);
  });

  it('records only the lines typed, each with the book quantity and the revisions it was read with', () => {
    const c = render(['ROLE_PHARMACIST']);
    startSheet(c);
    api.record.and.returnValue(of(count()));
    c.saveSheet();
    expect(toast.error).toHaveBeenCalledWith('STOCK_COUNT.SHEET.NOTHING_TYPED');
    expect(api.record).not.toHaveBeenCalled();

    const lot = c.sheet()!.find((line) => line.stockLotId === 'l-1')!;
    const noLot = c.sheet()!.find((line) => line.inventoryItemId === 'i-2')!;
    lot.counted = 76;
    noLot.counted = 12;
    expect(c.variance(lot)).toBe(-4);
    c.saveSheet();

    expect(api.record).toHaveBeenCalledTimes(2);
    const sent = api.record.calls.allArgs().map((args) => args[0]);
    const lotRequest = sent.find((r) => r.stockLotId === 'l-1')!;
    expect(lotRequest).toEqual(
      jasmine.objectContaining({
        inventoryItemId: 'i-1',
        expectedBookQuantity: 80,
        expectedUnit: 'comprimé',
        expectedItemRevision: 7,
        expectedLotRevision: 3,
        countedQuantity: 76,
        reason: 'Inventaire mensuel',
      }),
    );
    expect(sent.find((r) => r.inventoryItemId === 'i-2')).toEqual(
      jasmine.objectContaining({
        stockLotId: null,
        expectedLotRevision: null,
        countedQuantity: 12,
      }),
    );
    expect(lot.state).toBe('saved');
    expect(toast.success).toHaveBeenCalledWith('STOCK_COUNT.SHEET.SAVED');
    // A second save sends nothing more: the lines are recorded.
    c.saveSheet();
    expect(api.record).toHaveBeenCalledTimes(2);
  });

  it('a refused line says the server’s reason and keeps its request id for the retry; the others are recorded', () => {
    const c = render(['ROLE_PHARMACIST']);
    startSheet(c);
    const [first, second] = c.sheet()!.filter((line) => line.inventoryItemId === 'i-1');
    first.counted = 1;
    second.counted = 2;
    api.record.and.callFake((request) =>
      request.stockLotId === first.stockLotId
        ? throwError(
            () =>
              new HttpErrorResponse({
                status: 400,
                error: { message: 'Le stock ou son unité a changé depuis ce comptage.' },
              }),
          )
        : of(count()),
    );
    c.saveSheet();
    expect(first.state).toBe('refused');
    expect(first.message).toBe('Le stock ou son unité a changé depuis ce comptage.');
    expect(second.state).toBe('saved');
    const id = first.clientRequestId;
    c.saveSheet();
    expect(api.record.calls.mostRecent().args[0].clientRequestId).toBe(id);
  });

  it('refuses a negative or over-precise quantity before anything is sent', () => {
    const c = render(['ROLE_PHARMACIST']);
    startSheet(c);
    c.sheet()![0].counted = -1;
    c.saveSheet();
    expect(api.record).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('STOCK_COUNT.SHEET.INVALID_QUANTITY');
    expect(c.sheet()![0].state).toBe('refused');
  });

  it('confirms an adjustment only once the box is ticked, and says when the reader counted it themselves', () => {
    const c = render(['ROLE_STORE_MANAGER'], [count({ ownCount: true })]);
    api.confirm.and.returnValue(of(count({ status: 'APPLIED', appliedAt: '2026-11-27T06:00:00' })));
    c.openConfirm(c.counts()[0]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="count-own-note"]')).not.toBeNull();

    c.confirmAdjustment();
    expect(api.confirm).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('STOCK_COUNT.CONFIRM.CHECK_REQUIRED');

    c.confirmChecked = true;
    c.confirmAdjustment();
    expect(api.confirm).toHaveBeenCalledOnceWith('c-1', null);
    expect(toast.success).toHaveBeenCalledWith('STOCK_COUNT.CONFIRM.DONE');
    expect(c.confirming()).toBeNull();
  });

  it('a variance of a controlled drug needs a witness, sent with the confirmation', () => {
    const c = render(['ROLE_PHARMACIST'], [count({ controlled: true })]);
    api.confirm.and.returnValue(of(count({ status: 'APPLIED' })));
    c.openConfirm(c.counts()[0]);
    c.confirmChecked = true;
    c.confirmAdjustment();
    expect(api.confirm).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.WITNESS_REQUIRED');
    c.confirmWitness = 'u-2';
    c.confirmAdjustment();
    expect(api.confirm).toHaveBeenCalledOnceWith('c-1', 'u-2');
  });

  it('says the server’s refusal when the recorder may not confirm, and keeps the count waiting', () => {
    const c = render(['ROLE_PHARMACIST'], [count({ ownCount: true })]);
    api.confirm.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Un autre pharmacien doit confirmer cet inventaire.' },
          }),
      ),
    );
    c.openConfirm(c.counts()[0]);
    c.confirmChecked = true;
    c.confirmAdjustment();
    expect(toast.error).toHaveBeenCalledWith('Un autre pharmacien doit confirmer cet inventaire.');
    expect(c.confirming()).not.toBeNull();
  });

  it('QA D4191: a count that can no longer be applied is closed with a reason, or counted again', () => {
    const c = render(['ROLE_PHARMACIST'], [count({ status: 'OUTDATED' })]);
    expect(el.querySelector('[data-testid="count-outdated-note"]')).not.toBeNull();
    // An outdated count is never offered for confirmation.
    expect(el.querySelector('[data-testid="count-confirm"]')).toBeNull();
    expect(el.querySelector('[data-testid="count-recount"]')).not.toBeNull();

    api.close.and.returnValue(
      of(count({ status: 'CLOSED', closedByName: 'Awa Traoré', closeReason: 'Stock modifié' })),
    );
    el.querySelector<HTMLButtonElement>('[data-testid="count-close"]')!.click();
    fixture.detectChanges();
    expect(el.querySelector('app-reason-dialog')).not.toBeNull();
    c.closeCount(c.counts()[0], 'Stock modifié');
    fixture.detectChanges();

    expect(api.close).toHaveBeenCalledOnceWith('c-1', 'Stock modifié');
    expect(c.counts()[0].status).toBe('CLOSED');
    // Closed: it leaves « À traiter »; it is still read under « Clôturés », with who and why.
    expect(c.visibleCounts().length).toBe(0);
    c.countFilter = 'CLOSED';
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="count-close-reason"]')!.textContent).toContain(
      'Stock modifié',
    );
    expect(el.querySelector('[data-testid="count-close"]')).toBeNull();
  });

  const drift = (over: Partial<StockBalanceDrift> = {}): StockBalanceDrift => ({
    inventoryItemId: 'i-1',
    medicationName: 'Métronidazole 250 mg',
    unit: 'comprimé',
    controlled: false,
    quantityOnHand: 90,
    lotQuantity: 83,
    difference: -7,
    stockRevision: 7,
    lots: [
      { lotId: 'l-1', lotNumber: 'R81-A-1', expiryDate: '2027-06-30', remainingQuantity: 80 },
      { lotId: 'l-2', lotNumber: 'R81-A-2', expiryDate: '2027-01-31', remainingQuantity: 3 },
    ],
    ...over,
  });

  it('reconciles a drifted item from its lots as counted, with a reason and an explicit confirmation', () => {
    const c = render(['ROLE_STORE_MANAGER']);
    api.balanceDrift.and.returnValue(of([drift()]));
    api.reconcileBalance.and.returnValue(of('m-1'));
    c.setTab('balance');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="balance-row"]')!.textContent).toContain(
      'Métronidazole 250 mg',
    );

    c.openReconcile(c.drift()[0]);
    c.reconcile();
    expect(api.reconcileBalance).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('STOCK_COUNT.BALANCE.INCOMPLETE');

    // A lot that does not hold what the book says is counted first: nothing is sent.
    c.lotCounts = { 'l-1': 79, 'l-2': 3 };
    c.balanceReason = 'Écart constaté à l’inventaire';
    c.balanceConfirmed = true;
    c.reconcile();
    expect(api.reconcileBalance).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('STOCK_COUNT.BALANCE.LOT_DIFFERS');

    c.lotCounts = { 'l-1': 80, 'l-2': 3 };
    c.reconcile();
    expect(api.reconcileBalance).toHaveBeenCalledOnceWith('i-1', {
      expectedOnHand: 90,
      expectedLotQuantity: 83,
      expectedItemRevision: 7,
      confirmed: true,
      reason: 'Écart constaté à l’inventaire',
      witnessedBy: null,
      lotCounts: [
        { lotId: 'l-1', countedQuantity: 80 },
        { lotId: 'l-2', countedQuantity: 3 },
      ],
    });
    expect(toast.success).toHaveBeenCalledWith('STOCK_COUNT.BALANCE.DONE');
  });

  it('says nothing is to reconcile when no item drifted, and a failed read inline with a retry', () => {
    const c = render(['ROLE_PHARMACIST']);
    c.setTab('balance');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="balance-empty"]')).not.toBeNull();
    api.balanceDrift.and.returnValue(throwError(() => new Error('down')));
    c.loadDrift();
    fixture.detectChanges();
    expect(c.driftFailed()).toBeTrue();
    expect(el.querySelector('.workspace-error')).not.toBeNull();
  });

  it('each form pressed empty marks its fields and sends nothing; a field the server names is marked', () => {
    const c = render(['ROLE_PHARMACIST'], [count()]);
    document.body.appendChild(fixture.nativeElement);
    c.setTab('sheet');
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, { button: '[data-testid="count-start-submit"]' });
    expect(pharmacy.listLotsByPharmacy).not.toHaveBeenCalled();

    startSheet(c);
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="sheet-save"]',
      alsoMarked: [c.fieldId(c.visibleSheet()[0])],
      sent: api.record,
    });

    c.setTab('counts');
    c.openConfirm(c.counts()[0]);
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="count-confirm-submit"]',
      sent: api.confirm,
    });
    api.confirm.and.returnValue(
      throwError(() => fieldErrorsResponse({ confirmed: 'Confirmez explicitement l’ajustement.' })),
    );
    c.confirmChecked = true;
    c.confirmAdjustment();
    fixture.detectChanges();
    expectFieldsMarked(fixture, { countConfirmed: 'Confirmez explicitement l’ajustement.' });

    api.balanceDrift.and.returnValue(of([drift()]));
    c.setTab('balance');
    c.openReconcile(c.drift()[0]);
    fixture.detectChanges();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="balance-submit"]',
      sent: api.reconcileBalance,
    });
    fixture.nativeElement.remove();
  });

  it('asks before leaving a sheet that holds typed counts', async () => {
    const c = render(['ROLE_PHARMACIST']);
    startSheet(c);
    c.sheet()![0].counted = 5;
    confirm.ask.and.resolveTo(false);
    await c.closeSheet();
    expect(c.sheet()).not.toBeNull();
    confirm.ask.and.resolveTo(true);
    await c.closeSheet();
    expect(c.sheet()).toBeNull();
  });
});
