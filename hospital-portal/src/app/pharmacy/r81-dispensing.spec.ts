import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PharmacyCheckoutComponent } from './pharmacy-checkout';
import { PharmacyPaymentResponse, PharmacyService } from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { PatientIdLineComponent } from '../shared/patient-id-line/patient-id-line.component';
import { AllergyReviewStateComponent } from '../shared/allergy-acknowledgement/allergy-review-state.component';

/** QA r81, dispensing lane: D4185 (identity line), D4186 (receipt history), D3945 (voided review). */
describe('r81 dispensing lane', () => {
  describe('D4185: the patient identity line', () => {
    function render(mrn: string | null, dateOfBirth: string | null): HTMLElement {
      TestBed.configureTestingModule({
        imports: [PatientIdLineComponent, TranslateModule.forRoot()],
      });
      const fixture = TestBed.createComponent(PatientIdLineComponent);
      fixture.componentRef.setInput('mrn', mrn);
      fixture.componentRef.setInput('dateOfBirth', dateOfBirth);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it('shows the record number and the date of birth', () => {
      const el = render('TWP9080', '1990-03-12');
      expect(el.querySelector('[data-testid=patient-id-line-mrn]')).not.toBeNull();
      expect(el.querySelector('[data-testid=patient-id-line-dob]')).not.toBeNull();
    });

    it('leaves out the part the server did not send, and renders nothing when both are missing', () => {
      const onlyMrn = render('TWP9080', null);
      expect(onlyMrn.querySelector('[data-testid=patient-id-line-mrn]')).not.toBeNull();
      expect(onlyMrn.querySelector('[data-testid=patient-id-line-dob]')).toBeNull();
      TestBed.resetTestingModule();
      expect(render(null, null).querySelector('[data-testid=patient-id-line]')).toBeNull();
    });
  });

  describe('D4186: the checkout reopens a receipt and searches its fills', () => {
    let svc: jasmine.SpyObj<PharmacyService>;
    let fixture: ComponentFixture<PharmacyCheckoutComponent>;
    const paid: PharmacyPaymentResponse = {
      id: 'pay-1',
      receiptNumber: 'CHW-PH-2026-000012',
      dispenseId: 'd-9',
      patientId: 'p-1',
      hospitalId: 'h-1',
      paymentMethod: 'CASH',
      amount: 9000,
      currency: 'XOF',
      receivedBy: 'u-1',
      status: 'POSTED',
      createdAt: '2026-11-27T05:41:00',
      updatedAt: '2026-11-27T05:41:00',
      receipt: {
        medicationName: 'Sirop Paracétamol 120 mg/5 mL',
        quantity: 10,
        unit: 'flacon',
        unitPrice: 900,
        lineTotal: 9000,
        patientName: 'Awa Kaboré',
        patientMrn: 'TWP9080',
        duplicate: true,
      },
    };

    function create(): PharmacyCheckoutComponent {
      svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
        'getDispense',
        'createPayment',
        'getPayment',
        'listStores',
        'listDispensesByPharmacy',
        'listPaymentsByPharmacy',
      ]);
      svc.listStores.and.returnValue(of({ content: [{ id: 'ph-1', name: 'Main' }] }) as never);
      svc.listDispensesByPharmacy.and.returnValue(
        of({
          data: {
            content: [
              {
                id: 'd-1',
                status: 'PENDING',
                patientName: 'Awa Kaboré',
                patientMrn: 'TWP9080',
                medicationName: 'Amoxicilline',
                chargeAmount: 2500,
              },
              {
                id: 'd-2',
                status: 'PENDING',
                patientName: 'Awa Kaboré',
                patientMrn: 'TWP1111',
                medicationName: 'Paracétamol',
                chargeAmount: 300,
              },
            ],
          },
        }) as never,
      );
      svc.listPaymentsByPharmacy.and.returnValue(
        of({ data: { content: [paid], totalPages: 2 } }) as never,
      );
      svc.getPayment.and.returnValue(of({ data: paid }) as never);
      TestBed.configureTestingModule({
        imports: [PharmacyCheckoutComponent, TranslateModule.forRoot()],
        providers: [
          { provide: PharmacyService, useValue: svc },
          {
            provide: AuthService,
            useValue: { getHospitalId: () => 'h-1', getUserId: () => 'u-1' },
          },
          {
            provide: ToastService,
            useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
          },
        ],
      });
      fixture = TestBed.createComponent(PharmacyCheckoutComponent);
      fixture.detectChanges();
      return fixture.componentInstance;
    }

    it('lists the pharmacy payments with their receipt number and reopens one as a duplicate', () => {
      const c = create();
      expect(svc.listPaymentsByPharmacy).toHaveBeenCalledWith('ph-1', 0, 20);
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('[data-testid=receipt-history-number]')?.textContent).toContain(
        'CHW-PH-2026-000012',
      );
      (el.querySelector('[data-testid=receipt-history-open]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(svc.getPayment).toHaveBeenCalledWith('pay-1');
      expect(c.receipt()?.id).toBe('pay-1');
      expect(el.querySelector('[data-testid=receipt-duplicate]')).not.toBeNull();
      // The prices are the ones the server kept on the payment.
      expect(el.querySelector('[data-testid=receipt-unit-price]')).not.toBeNull();
      expect(el.querySelector('[data-testid=receipt-mrn]')?.textContent).toContain('TWP9080');
    });

    it('a payment just taken is not a duplicate', () => {
      const c = create();
      c.receipt.set({ ...paid, receipt: { ...paid.receipt, duplicate: false } });
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid=receipt-duplicate]'),
      ).toBeNull();
    });

    it('loads older payments after the first page', () => {
      const c = create();
      expect(c.historyMore()).toBeTrue();
      c.loadHistory(true);
      expect(svc.listPaymentsByPharmacy).toHaveBeenCalledWith('ph-1', 1, 20);
      expect(c.history().length).toBe(2);
    });

    it('narrows the fills by record number, name without accents, or medicine, and shows each amount', () => {
      const c = create();
      c.dispenseSearch.set('twp1111');
      expect(c.shownDispenses().map((d) => d.id)).toEqual(['d-2']);
      c.dispenseSearch.set('kabore');
      expect(c.shownDispenses().length).toBe(2);
      c.dispenseSearch.set('amoxi');
      expect(c.shownDispenses().map((d) => d.id)).toEqual(['d-1']);
      fixture.detectChanges();
      const option = (fixture.nativeElement as HTMLElement).querySelector(
        '#dispenseId option[value="d-1"]',
      );
      expect(option?.textContent).toContain('TWP9080');
      expect(option?.textContent).toMatch(/2\D?500/);
    });
  });

  describe('D3945: a review that no longer counts is explained', () => {
    function render(review: unknown): HTMLElement {
      TestBed.configureTestingModule({
        imports: [AllergyReviewStateComponent, TranslateModule.forRoot()],
      });
      const fixture = TestBed.createComponent(AllergyReviewStateComponent);
      fixture.componentRef.setInput('review', review);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it('says a review existed and shows the server reason', () => {
      const el = render({
        state: 'AWAITING_REVIEW',
        voidedDecision: 'CONTINUE',
        voidedReviewedByName: 'Aminata Ouedraogo',
        voidedReviewedAt: '2026-11-10T09:30:00',
        voidReason: 'ALLERGY_CHANGED',
        voidReasonText:
          'L’allergie du patient a été modifiée après cette revue : elle ne s’applique plus.',
      });
      expect(el.querySelector('[data-testid=allergy-review-state-voided]')).not.toBeNull();
      expect(
        el.querySelector('[data-testid=allergy-review-state-void-reason]')?.textContent,
      ).toContain('modifiée après cette revue');
    });

    it('shows nothing more when no review was ever recorded', () => {
      const el = render({ state: 'AWAITING_REVIEW' });
      expect(el.querySelector('[data-testid=allergy-review-state-voided]')).toBeNull();
    });
  });
});
