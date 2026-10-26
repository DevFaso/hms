import { TestBed } from '@angular/core/testing';
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
    const c = TestBed.createComponent(PharmacyCheckoutComponent).componentInstance;
    c.ngOnInit();
    c.pickDispense('d-1');
    return c;
  }

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
    expect(svc.listDispensesByPharmacy).toHaveBeenCalledWith('ph-1', 0, 50);
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
});
