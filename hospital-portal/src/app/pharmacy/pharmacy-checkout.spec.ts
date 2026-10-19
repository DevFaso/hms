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

  function create(chargeAmount: number | null): PharmacyCheckoutComponent {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'getDispense',
      'createPayment',
    ]);
    svc.getDispense.and.returnValue(
      of({
        data: { id: 'd-1', patientId: 'p-1', quantityDispensed: 9, chargeAmount },
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
    c.dispenseId = 'd-1';
    c.loadDispense();
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
});
