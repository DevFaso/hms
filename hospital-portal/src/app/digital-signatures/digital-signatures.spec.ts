import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { DigitalSignaturesComponent } from './digital-signatures';
import {
  DigitalSignatureService,
  SignableReport,
  SignatureResponse,
} from '../services/digital-signature.service';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelService } from '../core/enum-label.service';

/** QA D627: the sign modal picks a report; it never asks for, nor shows, a raw id. */
describe('DigitalSignaturesComponent sign picker (QA D627)', () => {
  let fixture: ComponentFixture<DigitalSignaturesComponent>;
  let svc: jasmine.SpyObj<DigitalSignatureService>;

  const reportId = '0f3c8e2a-1b2c-4d5e-8f90-123456789abc';
  const rows: SignableReport[] = [
    {
      reportType: 'DISCHARGE_SUMMARY',
      reportId,
      patientName: 'Awa Traore',
      createdAt: '2026-10-20T09:00:00',
    },
  ];

  function setup(list: SignableReport[]): HTMLElement {
    svc = jasmine.createSpyObj<DigitalSignatureService>('DigitalSignatureService', [
      'listSignable',
      'listByProvider',
      'listAll',
      'sign',
    ]);
    svc.listSignable.and.returnValue(of(list));
    svc.listByProvider.and.returnValue(of([]));
    svc.listAll.and.returnValue(of([]));
    svc.sign.and.returnValue(of({ id: 's1' } as unknown as SignatureResponse));
    TestBed.configureTestingModule({
      imports: [DigitalSignaturesComponent, TranslateModule.forRoot()],
      providers: [
        { provide: DigitalSignatureService, useValue: svc },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'info']),
        },
        { provide: AuthService, useValue: { getUserProfile: () => ({ staffId: 'staff-1' }) } },
        {
          provide: RoleContextService,
          useValue: { hasAnyActiveRole: () => true, activeHospitalId: 'hosp-1' },
        },
        { provide: EnumLabelService, useValue: { transform: (v: string) => `label:${v}` } },
      ],
    });
    fixture = TestBed.createComponent(DigitalSignaturesComponent);
    fixture.detectChanges();
    fixture.componentInstance.openSign();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists the signable reports by type and patient, with no id field and no id text', () => {
    const el = setup(rows);
    expect(svc.listSignable).toHaveBeenCalled();
    expect(el.querySelector('#sign-report-id')).toBeNull();
    const options = Array.from(el.querySelectorAll('#sign-report option')).map(
      (o) => o.textContent ?? '',
    );
    expect(options.length).toBe(2);
    expect(options[1]).toContain('label:DISCHARGE_SUMMARY');
    expect(options[1]).toContain('Awa Traore');
    expect(el.textContent).not.toContain(reportId);
  });

  it('signs the picked report with its type and id', () => {
    setup(rows);
    const cmp = fixture.componentInstance;
    cmp.pickSignable(cmp.signableKey(rows[0]));
    cmp.signValue.set('Ada Lovelace');
    cmp.doSign();
    expect(svc.sign).toHaveBeenCalledWith(
      jasmine.objectContaining({ reportType: 'DISCHARGE_SUMMARY', reportId, hospitalId: 'hosp-1' }),
    );
  });

  it('says so when there is nothing to sign', () => {
    const el = setup([]);
    expect(el.querySelector('#sign-report')).toBeNull();
    expect(el.querySelector('[data-testid="no-signable"]')).not.toBeNull();
  });
});
