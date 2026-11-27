import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

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

  function setup(list: SignableReport[], roles?: string[]): HTMLElement {
    svc = jasmine.createSpyObj<DigitalSignatureService>('DigitalSignatureService', [
      'listSignable',
      'listByProvider',
      'listAll',
      'listByReport',
      'sign',
      'verify',
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
          useValue: {
            hasAnyActiveRole: (wanted: string[]) =>
              roles ? wanted.some((r) => roles.includes(r)) : true,
            activeHospitalId: 'hosp-1',
          },
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

  it('cancels an obsolete lookup and separates an unavailable result from unsigned', () => {
    setup([]);
    const component = fixture.componentInstance;
    const pending = new Subject<SignatureResponse[]>();
    svc.listByReport.and.returnValue(pending);
    component.lookupReportType.set('DISCHARGE_SUMMARY');
    component.lookupReportId.set(reportId);
    component.runLookup();
    component.onLookupChange();
    pending.next([]);
    expect(component.lookupDone()).toBeFalse();
    svc.listByReport.and.returnValue(throwError(() => new Error('offline')));
    component.runLookup();
    expect(component.lookupError()).toBeTrue();
    expect(component.lookupSigned()).toBeNull();
  });

  it('restarts an in-flight signature read so an obsolete response cannot replace a requested refresh', () => {
    setup([]);
    const component = fixture.componentInstance;
    const old = new Subject<SignatureResponse[]>();
    svc.listAll.and.returnValue(old);
    component.loadSignatures();
    svc.listAll.and.returnValue(of([{ id: 'fresh' } as SignatureResponse]));
    component.loadSignatures();
    old.next([{ id: 'obsolete' } as SignatureResponse]);
    expect(component.signatures().map((s) => s.id)).toEqual(['fresh']);
    expect(component.loading()).toBeFalse();
  });

  it('rejects an invalid report UUID before requesting a lookup', () => {
    setup([]);
    const component = fixture.componentInstance;
    component.lookupReportType.set('DISCHARGE_SUMMARY');
    component.lookupReportId.set('bad-id');
    component.runLookup();
    expect(component.lookupValidation()).toBeTrue();
    expect(svc.listByReport).not.toHaveBeenCalled();
  });

  it('clears loaded reports and counters when a subsequent signature reload fails', () => {
    const el = setup([]);
    const component = fixture.componentInstance;
    svc.listAll.and.returnValue(
      of([
        { id: 'signed', reportId, reportType: 'DISCHARGE_SUMMARY', status: 'SIGNED' },
        { id: 'revoked', reportId: 'other', reportType: 'LAB_RESULT', status: 'REVOKED' },
      ] as SignatureResponse[]),
    );
    component.loadSignatures();
    fixture.detectChanges();
    expect(component.signedCount()).toBe(1);
    expect(component.revokedCount()).toBe(1);
    expect(el.querySelector('#known-report')).not.toBeNull();

    svc.listAll.and.returnValue(throwError(() => new Error('offline')));
    component.loadSignatures();
    fixture.detectChanges();
    expect(component.loadError()).toBeTrue();
    expect(component.signatures()).toEqual([]);
    expect(component.knownReports()).toEqual([]);
    expect(component.signedCount()).toBe(0);
    expect(component.revokedCount()).toBe(0);
    expect(el.querySelector('#known-report')).toBeNull();
  });

  it('renders verification unavailable after a transport failure without declaring the signature invalid', () => {
    const el = setup([]);
    const component = fixture.componentInstance;
    component.openVerify({
      id: 'signed',
      reportId,
      reportType: 'DISCHARGE_SUMMARY',
    } as SignatureResponse);
    component.verifyValue.set('signature');
    svc.verify.and.returnValue(throwError(() => new Error('offline')));
    component.doVerify();
    fixture.detectChanges();
    expect(component.verifyResult()).toBeNull();
    expect(component.verifyError()).toBeTrue();
    expect(component.verifying()).toBeFalse();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain(
      'SIGNATURES.VERIFY_UNAVAILABLE',
    );
  });

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
  // Review #1196/#1198 L3: the known-report select was bound to a constant ''
  // and kept showing a stale report after Clear.
  it('resets the known-report choice when the lookup is cleared or edited', () => {
    const el = setup([]);
    const component = fixture.componentInstance;
    svc.listAll.and.returnValue(
      of([
        { id: 'sig-1', reportId, reportType: 'DISCHARGE_SUMMARY', status: 'SIGNED' },
      ] as SignatureResponse[]),
    );
    component.loadSignatures();
    fixture.detectChanges();
    const select = el.querySelector('#known-report') as HTMLSelectElement;
    select.value = select.options[1].value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(component.knownReportChoice()).toBe('sig-1');
    expect(component.lookupReportId()).toBe(reportId);

    component.clearLookup();
    fixture.detectChanges();
    expect(component.knownReportChoice()).toBe('');
    expect(component.lookupReportId()).toBe('');

    component.chooseKnownReport('sig-1');
    component.onLookupChange();
    expect(component.knownReportChoice()).toBe('');
  });

  // Review #1196/#1198 L4: GET /signatures/report/{type}/{id} admits clinicians only.
  for (const [role, shown] of [
    ['ROLE_DOCTOR', true],
    ['ROLE_PHARMACIST', true],
    ['ROLE_HOSPITAL_ADMIN', false],
    ['ROLE_SUPER_ADMIN', false],
  ] as const) {
    it(`${shown ? 'shows' : 'hides'} the report lookup for ${role}`, () => {
      const el = setup([], [role]);
      expect(fixture.componentInstance.canLookup).toBe(shown);
      expect(el.querySelector('.lookup-panel') !== null).toBe(shown);
      expect(el.querySelector('#lookup-id') !== null).toBe(shown);
      expect(el.querySelector('[data-testid="empty-lookup-help"]') !== null).toBe(shown);
    });
  }
});
