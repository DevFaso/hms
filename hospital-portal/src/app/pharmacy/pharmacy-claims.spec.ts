import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PharmacyClaimsComponent } from './pharmacy-claims';
import { PharmacyClaimResponse, PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';

describe('PharmacyClaimsComponent', () => {
  let fixture: ComponentFixture<PharmacyClaimsComponent>;

  function claim(overrides: Partial<PharmacyClaimResponse> = {}): PharmacyClaimResponse {
    return {
      id: 'c-1',
      dispenseId: 'd-1',
      patientId: '3f2a9c1b-1111-4000-8000-000000000000',
      hospitalId: 'h-1',
      claimStatus: 'DRAFT',
      amount: 1500,
      currency: 'XOF',
      createdAt: '2026-10-27T09:00:00',
      updatedAt: '2026-10-27T09:00:00',
      ...overrides,
    };
  }

  async function render(rows: PharmacyClaimResponse[]): Promise<HTMLElement> {
    const pharmacy = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listClaimsByHospital',
    ]);
    pharmacy.listClaimsByHospital.and.returnValue(of({ data: { content: rows } }) as never);
    await TestBed.configureTestingModule({
      imports: [PharmacyClaimsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: pharmacy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PharmacyClaimsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('QA D992 class: names the patient, never a slice of the patient id', async () => {
    const el = await render([claim({ patientName: 'Awa Test' })]);
    const text = el.querySelector('tbody')?.textContent ?? '';
    expect(text).toContain('Awa Test');
    expect(text).not.toContain('3f2a9c1b');
  });

  it('QA D992 class: a claim whose patient has no name says so', async () => {
    const el = await render([claim({ patientName: null })]);
    const text = el.querySelector('tbody')?.textContent ?? '';
    expect(text).toContain('PHARMACY_CLAIMS.PATIENT_UNNAMED');
    expect(text).not.toContain('3f2a9c1b');
  });
});
