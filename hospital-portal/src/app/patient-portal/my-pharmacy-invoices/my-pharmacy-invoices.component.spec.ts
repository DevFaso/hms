import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { MyPharmacyInvoicesComponent } from './my-pharmacy-invoices.component';
import {
  PharmacyClaimResponse,
  PharmacyService,
  PharmacyPaymentResponse,
} from '../../services/pharmacy.service';

describe('MyPharmacyInvoicesComponent', () => {
  let component: MyPharmacyInvoicesComponent;
  let fixture: ComponentFixture<MyPharmacyInvoicesComponent>;
  let pharmacyServiceSpy: jasmine.SpyObj<PharmacyService>;

  const samplePayments: PharmacyPaymentResponse[] = [
    {
      id: 'p1',
      dispenseId: 'd1',
      patientId: 'pat1',
      amount: 1500,
      currency: 'XOF',
      method: 'CASH',
      status: 'COMPLETED',
      reference: 'REF-1',
      paidAt: '2026-04-01T10:00:00Z',
    } as unknown as PharmacyPaymentResponse,
    {
      id: 'p2',
      dispenseId: 'd2',
      patientId: 'pat1',
      amount: 500,
      currency: 'XOF',
      method: 'MOBILE_MONEY',
      status: 'COMPLETED',
      reference: 'REF-2',
      paidAt: '2026-04-02T10:00:00Z',
    } as unknown as PharmacyPaymentResponse,
  ];

  const sampleClaims: PharmacyClaimResponse[] = [
    {
      id: 'c1',
      dispenseId: 'd1',
      patientId: 'pat1',
      hospitalId: 'h1',
      claimStatus: 'SUBMITTED',
      amount: 1200,
      currency: 'XOF',
      coverageReference: 'COV-1',
      createdAt: '2026-04-03T10:00:00Z',
      updatedAt: '2026-04-03T10:00:00Z',
    },
  ];

  async function build(
    paymentResponseFactory: () => unknown,
    claimResponseFactory: () => unknown = () =>
      of({ success: true, data: { content: [], totalElements: 0, number: 0, size: 50 } }),
  ): Promise<void> {
    pharmacyServiceSpy = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listMyPharmacyPayments',
      'listMyPharmacyClaims',
    ]);
    pharmacyServiceSpy.listMyPharmacyPayments.and.callFake(paymentResponseFactory as never);
    pharmacyServiceSpy.listMyPharmacyClaims.and.callFake(claimResponseFactory as never);

    await TestBed.configureTestingModule({
      imports: [MyPharmacyInvoicesComponent],
      providers: [{ provide: PharmacyService, useValue: pharmacyServiceSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyPharmacyInvoicesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads pharmacy payments from the /me endpoint and totals them', async () => {
    await build(
      () =>
        of({
          success: true,
          data: { content: samplePayments, totalElements: 2, number: 0, size: 50 },
        }),
      () =>
        of({
          success: true,
          data: { content: sampleClaims, totalElements: 1, number: 0, size: 50 },
        }),
    );

    expect(pharmacyServiceSpy.listMyPharmacyPayments).toHaveBeenCalledWith(0, 50);
    expect(pharmacyServiceSpy.listMyPharmacyClaims).toHaveBeenCalledWith(0, 50);
    expect(component.payments().length).toBe(2);
    expect(component.claims().length).toBe(1);
    expect(component.totalPaid()).toBe(2000);
    expect(component.totalClaimed()).toBe(1200);
    expect(component.loading()).toBe(false);
  });

  it('renders an empty state when the patient has no payments', async () => {
    await build(() =>
      of({ success: true, data: { content: [], totalElements: 0, number: 0, size: 50 } }),
    );

    expect(component.payments().length).toBe(0);
    expect(component.totalPaid()).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('clears the loading flag when the API errors', async () => {
    await build(() => throwError(() => new Error('boom')));

    expect(component.loading()).toBe(false);
    expect(component.payments().length).toBe(0);
  });
});
