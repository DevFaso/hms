import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';
import { DigitalSignaturesComponent } from './digital-signatures';
import { DigitalSignatureService, SignatureResponse } from '../services/digital-signature.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

const reportId = '12345678-1234-1234-1234-123456789abc';
const signature = {
  id: 'sig1',
  reportId,
  reportType: 'LAB_RESULT',
  signedByName: 'Dr Test',
  status: 'SIGNED',
} as SignatureResponse;

describe('DigitalSignaturesComponent feedback', () => {
  let fixture: ComponentFixture<DigitalSignaturesComponent>;
  let component: DigitalSignaturesComponent;
  let service: jasmine.SpyObj<DigitalSignatureService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj('DigitalSignatureService', [
      'listAll',
      'listByReport',
      'verify',
    ]);
    service.listAll.and.returnValue(of([signature]));
    service.listByReport.and.returnValue(of([signature]));
    await TestBed.configureTestingModule({
      imports: [DigitalSignaturesComponent, TranslateModule.forRoot()],
      providers: [
        { provide: DigitalSignatureService, useValue: service },
        { provide: AuthService, useValue: { getUserProfile: () => ({ staffId: 'staff1' }) } },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (roles: string[]) => roles.includes('ROLE_HOSPITAL_ADMIN'),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['error', 'success']),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DigitalSignaturesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('prefills report references from known signatures and deduplicates reports', () => {
    component.signatures.set([signature, { ...signature, id: 'sig2' }]);
    expect(component.knownReports().length).toBe(1);
    component.chooseKnownReport('sig1');
    expect(component.lookupReportType()).toBe('LAB_RESULT');
    expect(component.lookupReportId()).toBe(reportId);
  });

  it('validates UUIDs without sending a request', () => {
    component.lookupReportType.set('LAB_RESULT');
    component.lookupReportId.set('wrong');
    component.runLookup();
    fixture.detectChanges();
    expect(component.lookupValidation()).toBeTrue();
    expect(service.listByReport).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('#lookup-id').getAttribute('aria-invalid')).toBe(
      'true',
    );
  });

  it('does not describe failed lookups as unsigned reports and allows retry', () => {
    component.chooseKnownReport('sig1');
    service.listByReport.and.returnValue(throwError(() => new Error('offline')));
    component.runLookup();
    fixture.detectChanges();
    const result = fixture.nativeElement.querySelector('.lookup-result');
    expect(result.getAttribute('role')).toBe('alert');
    expect(result.textContent).not.toContain('SIGNATURES.REPORT_NOT_SIGNED');
    expect(result.textContent).not.toContain('SIGNATURES.LOOKUP_NONE');
    service.listByReport.and.returnValue(of([]));
    result.querySelector('button').click();
    fixture.detectChanges();
    expect(component.lookupError()).toBeFalse();
    expect(component.lookupSigned()).toBeFalse();
  });

  it('cancels lookup results when the user changes report fields', () => {
    const response = new Subject<SignatureResponse[]>();
    service.listByReport.and.returnValue(response);
    component.chooseKnownReport('sig1');
    component.runLookup();
    component.runLookup();
    expect(service.listByReport).toHaveBeenCalledTimes(1);
    component.onLookupChange();
    expect(response.observed).toBeFalse();
    expect(component.lookupLoading()).toBeFalse();
    expect(component.lookupDone()).toBeFalse();
  });

  it('shows list failures separately from empty results and retries', () => {
    service.listAll.and.returnValue(throwError(() => new Error('offline')));
    component.loadSignatures();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.load-error')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.empty-state')).toBeNull();
    service.listAll.and.returnValue(of([]));
    fixture.nativeElement.querySelector('.load-error button').click();
    fixture.detectChanges();
    expect(component.loadError()).toBeFalse();
    expect(fixture.nativeElement.querySelector('.empty-state').textContent).toContain(
      'SIGNATURES.EMPTY_HELP',
    );
  });

  it('distinguishes filtered empty states and restores all filters', () => {
    component.search.set('  nobody  ');
    component.statusFilter.set('REVOKED');
    component.typeFilter.set('PROGRESS_NOTE');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty-state').textContent).toContain(
      'SIGNATURES.NO_MATCHES',
    );
    fixture.nativeElement.querySelector('.empty-state button').click();
    fixture.detectChanges();
    expect(component.hasFilters()).toBeFalse();
    expect(component.filteredSignatures().length).toBe(1);
  });
  it('reports unavailable verification without labeling a signature invalid', () => {
    service.verify.and.returnValue(throwError(() => new Error('offline')));
    component.openVerify(signature);
    component.verifyValue.set('Dr Test');
    component.doVerify();
    fixture.detectChanges();
    expect(component.verifyError()).toBeTrue();
    expect(component.verifyResult()).toBeNull();
    expect(fixture.nativeElement.querySelector('.verify-result.invalid')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('SIGNATURES.VERIFY_UNAVAILABLE');
  });
});
