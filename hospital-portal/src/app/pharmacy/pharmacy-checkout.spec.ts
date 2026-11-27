import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of } from 'rxjs';
import { PharmacyCheckoutComponent } from './pharmacy-checkout';
import { PharmacyService } from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';

type DispenseResult =
  ReturnType<PharmacyService['getDispense']> extends Observable<infer T> ? T : never;

describe('PharmacyCheckoutComponent (QA D264)', () => {
  let svc: jasmine.SpyObj<PharmacyService>;
  let fixture: ComponentFixture<PharmacyCheckoutComponent>;

  function create(
    chargeAmount: number | null,
    paid: boolean | null = false,
  ): PharmacyCheckoutComponent {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'getDispense',
      'createPayment',
      'listPharmacies',
      'listDispensesByPharmacy',
    ]);
    svc.listPharmacies.and.returnValue(of({ content: [{ id: 'ph-1', name: 'Main' }] }) as never);
    svc.listDispensesByPharmacy.and.returnValue(
      of({
        data: {
          content: [
            { id: 'd-1', status: 'COMPLETED', medicationName: 'Paracétamol' },
            { id: 'd-2', status: 'CANCELLED', medicationName: 'Paracétamol' },
            // QA D843: dispense e9a33ec4 was paid 3 600 F CFA cash and still listed.
            {
              id: 'e9a33ec4',
              status: 'COMPLETED',
              medicationName: 'Artéméther-Luméfantrine',
              paid: true,
            },
          ],
        },
      }) as never,
    );
    svc.getDispense.and.returnValue(
      of({
        data: { id: 'd-1', patientId: 'p-1', quantityDispensed: 9, chargeAmount, paid },
      } as unknown as DispenseResult),
    );
    TestBed.configureTestingModule({
      imports: [PharmacyCheckoutComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: AuthService, useValue: { getHospitalId: () => 'h-1', getUserId: () => 'u-1' } },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
      ],
    });
    fixture = TestBed.createComponent(PharmacyCheckoutComponent);
    const c = fixture.componentInstance;
    c.ngOnInit();
    c.pickDispense('d-1');
    return c;
  }

  it('PR 11 (QA D2430): every press for one dispense carries the same key', () => {
    const c = create(3600, false);
    svc.createPayment.and.returnValue(of({ data: { id: 'pay-1' } }) as never);
    c.submit();
    const first = svc.createPayment.calls.mostRecent().args[0].clientRequestId;
    expect(first).toEqual(jasmine.any(String));
    c.loadDispense(); // the same dispense loaded again keeps its key
    c.submit();
    expect(svc.createPayment.calls.mostRecent().args[0].clientRequestId).toBe(first);
  });

  it('takes the amount from the dispense charge and fixes it', () => {
    const c = create(2250);
    expect(c.amount).toBe(2250);
    expect(c.hasCharge()).toBeTrue();
  });

  it('leaves the amount empty when the medication has no price', () => {
    const c = create(null);
    expect(c.amount).toBeNull();
    expect(c.hasCharge()).toBeFalse();
  });

  it('QA D416: lists the pharmacy fills to pick from, without the cancelled ones', () => {
    const c = create(2250);
    expect(svc.listDispensesByPharmacy).toHaveBeenCalledWith('ph-1', 0, 200); // QA D1347: every page
    expect(c.dispenses().map((d) => d.id)).toEqual(['d-1']);
    expect(svc.getDispense).toHaveBeenCalledWith('d-1');
  });

  it('QA D843: leaves a paid fill out of the list', () => {
    const c = create(3600);
    expect(c.dispenses().map((d) => d.id)).not.toContain('e9a33ec4');
  });

  it('QA D843: a paid fill opened anyway says so and takes no second payment', () => {
    const c = create(3600, true);
    expect(c.isPaid()).toBeTrue();
    c.submit();
    expect(svc.createPayment).not.toHaveBeenCalled();
  });

  it('QA D843: an unpaid priced fill is still paid normally', () => {
    const c = create(3600, false);
    svc.createPayment.and.returnValue(of({ data: { id: 'pay-1' } }) as never);
    c.submit();
    expect(svc.createPayment).toHaveBeenCalledWith(
      jasmine.objectContaining({ dispenseId: 'd-1', amount: 3600 }),
    );
  });

  it('QA D1033: a mobile-money payment needs the typed reference, and sends it as typed', () => {
    const c = create(3600, false);
    svc.createPayment.and.returnValue(of({ data: { id: 'pay-1' } }) as never);
    c.paymentMethod = 'MOBILE_MONEY';
    c.referenceNumber = '   ';
    c.submit();
    expect(svc.createPayment).not.toHaveBeenCalled();

    c.referenceNumber = ' OM-R17-662001 ';
    // Billing plan PR 9: the operator is required too.
    c.submit();
    expect(svc.createPayment).not.toHaveBeenCalled();
    c.mobileMoneyOperator = 'ORANGE_MONEY';
    c.submit();
    expect(svc.createPayment).toHaveBeenCalledWith(
      jasmine.objectContaining({
        paymentMethod: 'MOBILE_MONEY',
        referenceNumber: 'OM-R17-662001',
        mobileMoneyOperator: 'ORANGE_MONEY',
      }),
    );
  });

  it('QA D1306: a blank Mobile Money reference marks the field, in the same press as the amount', () => {
    const c = create(null, false);
    // The first render runs ngOnInit again, which reloads the pharmacy and clears the pick.
    fixture.detectChanges();
    c.pickDispense('d-1');
    c.paymentMethod = 'MOBILE_MONEY';
    c.referenceNumber = '';
    fixture.detectChanges();
    c.submit();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(svc.createPayment).not.toHaveBeenCalled();
    expect(host.querySelector('#ref')!.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#amount')!.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#ref-invalid-msg')!.textContent).toBe(
      'PHARMACY_CHECKOUT.REFERENCE_REQUIRED',
    );
  });

  it('QA D1032: the receipt shows the server receipt number, never a slice of the payment id', () => {
    const c = create(3600, false);
    const fixture = TestBed.createComponent(PharmacyCheckoutComponent);
    const view = fixture.componentInstance;
    view.receipt.set({
      id: '38d0fcfe-16aa-4b33-8f43-37e1658a240d',
      receiptNumber: 'CHW75C64-PH-2026-000012',
      dispenseId: 'd-1',
      patientId: 'p-1',
      hospitalId: 'h-1',
      paymentMethod: 'CASH',
      amount: 3600,
    } as never);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).querySelector('.receipt-meta')?.textContent;
    expect(text).toContain('CHW75C64-PH-2026-000012');
    expect(text).not.toContain('38D0FCFE16');
    expect(c).toBeTruthy();
  });

  it('QA D2310: the receipt prints quantity, unit price, record number, cashier and the pharmacy', () => {
    create(375, false);
    const fixture = TestBed.createComponent(PharmacyCheckoutComponent);
    const view = fixture.componentInstance;
    view.receipt.set({
      id: 'pay-1',
      receiptNumber: 'CHWF2396-PH-2026-000003',
      dispenseId: 'd-1',
      patientId: 'p-1',
      hospitalId: 'h-1',
      paymentMethod: 'CASH',
      amount: 75,
      currency: 'XOF',
      receipt: {
        medicationName: 'Paracétamol 500 mg',
        quantity: 15,
        unit: 'comprimé',
        unitPrice: 25,
        lineTotal: 375,
        insurerShare: 300,
        patientMrn: 'CHW-000123',
        cashierName: 'Awa Traoré',
        pharmacyName: 'Pharmacie du CHU',
        pharmacyAddress: 'Avenue Kwame Nkrumah, Ouagadougou',
        pharmacyPhone: '+22625300000',
      },
    } as never);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const text = (testId: string): string =>
      host.querySelector(`[data-testid="${testId}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ??
      '';

    expect(text('receipt-quantity')).toContain('15');
    expect(text('receipt-unit-price')).toContain('25');
    expect(text('receipt-line-total')).toContain('375');
    expect(text('receipt-insurer-share')).toContain('300');
    expect(text('receipt-mrn')).toBe('CHW-000123');
    expect(text('receipt-cashier')).toBe('Awa Traoré');
    expect(text('receipt-pharmacy')).toContain('Pharmacie du CHU');
    expect(text('receipt-pharmacy')).toContain('Avenue Kwame Nkrumah, Ouagadougou');
    // What the patient paid stays the total of the receipt.
    expect(host.querySelector('.total-row .amount')?.textContent).toContain('75');
  });

  it('QA D2310: a receipt the server sent no lines for prints no empty line', () => {
    create(375, false);
    const fixture = TestBed.createComponent(PharmacyCheckoutComponent);
    fixture.componentInstance.receipt.set({
      id: 'pay-2',
      receiptNumber: 'CHWF2396-PH-2026-000004',
      dispenseId: 'd-1',
      patientId: 'p-1',
      hospitalId: 'h-1',
      paymentMethod: 'CASH',
      amount: 375,
    } as never);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    for (const testId of [
      'receipt-unit-price',
      'receipt-line-total',
      'receipt-insurer-share',
      'receipt-mrn',
      'receipt-cashier',
    ]) {
      expect(host.querySelector(`[data-testid="${testId}"]`))
        .withContext(testId)
        .toBeNull();
    }
  });

  it('QA D1058: the fill picker names the patient before the medicine', () => {
    create(3600, false);
    const fixture = TestBed.createComponent(PharmacyCheckoutComponent);
    const view = fixture.componentInstance;
    fixture.detectChanges();
    view.dispenses.set([
      {
        id: 'd-9',
        medicationName: 'Paracétamol',
        patientName: 'Salif Kaboré',
        quantityDispensed: 9,
      },
    ] as never);
    fixture.detectChanges();
    const option = (fixture.nativeElement as HTMLElement).querySelector(
      '#dispenseId option[value="d-9"]',
    );
    expect(option?.textContent).toContain('Salif Kaboré');
    expect(option?.textContent?.indexOf('Salif')).toBeLessThan(
      option?.textContent?.indexOf('Paracétamol') ?? 0,
    );
  });

  it('exposes the current lookup/payment step and hides steps on the receipt', () => {
    const c = create(2250);
    fixture.detectChanges();
    c.pickDispense('d-1');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.workspace-steps [aria-current="step"]').length).toBe(1);
    expect(
      el.querySelector('.workspace-steps li:nth-child(2)')?.classList.contains('current'),
    ).toBeTrue();
    expect(el.querySelector('.workspace-steps li:nth-child(2)')?.getAttribute('aria-current')).toBe(
      'step',
    );
    c.reset();
    fixture.detectChanges();
    expect(el.querySelector('.workspace-steps li:first-child')?.getAttribute('aria-current')).toBe(
      'step',
    );
    c.pickDispense('d-1');
    svc.createPayment.and.returnValue(
      of({
        data: { id: 'pay-fixture', amount: 2250, currency: 'XOF', paymentMethod: 'CASH' },
      }) as never,
    );
    c.submit();
    fixture.detectChanges();
    expect(el.querySelector('.workspace-steps')).toBeNull();
    expect(el.querySelector('.receipt')).not.toBeNull();
  });
});
