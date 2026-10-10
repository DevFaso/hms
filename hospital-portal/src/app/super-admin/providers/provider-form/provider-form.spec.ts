import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { evidenceOf } from '../../../services/provider.model';
import { SuperAdminProviderService } from '../../../services/super-admin-provider.service';
import { providerFixture } from '../../../testing/provider-fixtures';
import { ProviderEvidenceFormComponent } from '../provider-evidence-form/provider-evidence-form';
import { ProviderFormComponent } from './provider-form';

describe('ProviderFormComponent', () => {
  let service: jasmine.SpyObj<SuperAdminProviderService>;

  function setup(id: string | null): { cmp: ProviderFormComponent; host: HTMLElement } {
    TestBed.configureTestingModule({
      imports: [ProviderFormComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: SuperAdminProviderService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap(id ? { id } : {}) } },
        },
      ],
    });
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    const fixture = TestBed.createComponent(ProviderFormComponent);
    fixture.detectChanges();
    return { cmp: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<SuperAdminProviderService>('SuperAdminProviderService', [
      'get',
      'create',
      'resubmit',
    ]);
    service.create.and.returnValue(of(providerFixture({ id: 'new-1' })));
    service.resubmit.and.returnValue(of(providerFixture()));
    service.get.and.returnValue(of(providerFixture({ verificationStatus: 'REJECTED' })));
  });

  afterEach(() => TestBed.resetTestingModule());

  it('creates: refuses an incomplete form without calling the server', () => {
    const { cmp } = setup(null);
    expect(cmp.resubmitting()).toBeFalse();
    cmp.submit();
    expect(cmp.errorKey()).toBe('PROVIDER.FORM.REQUIRED_FIELDS');
    expect(service.create).not.toHaveBeenCalled();
  });

  it('creates with the type, code and both evidence layers, then opens the new facility', () => {
    const { cmp } = setup(null);
    cmp.facilityType.set('LABORATORY');
    cmp.code.set(' lab-1 ');
    cmp.email.set('');
    cmp.evidence.set(evidenceOf(providerFixture()));

    cmp.submit();

    const body = service.create.calls.mostRecent().args[0];
    expect(body.facilityType).toBe('LABORATORY');
    expect(body.code).toBe('lab-1');
    expect(body.email).toBeNull();
    expect(body.business.rccmNumber).toBe('BF-OUA-1');
    expect(body.professional.licenceExpiresOn).toBeNull();
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith([
      '/super-admin/providers',
      'new-1',
    ]);
  });

  it('shows the server message when the create is refused', () => {
    service.create.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Code taken' } })),
    );
    const { cmp } = setup(null);
    cmp.code.set('PH');
    cmp.evidence.set(evidenceOf(providerFixture()));

    cmp.submit();

    expect(cmp.errorText()).toBe('Code taken');
    expect(cmp.errorKey()).toBe('PROVIDER.FORM.SAVE_FAILED');
    expect(cmp.saving()).toBeFalse();
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled();
  });

  it('resubmits: prefilled from the current evidence, no code needed', () => {
    const { cmp } = setup('p-1');
    expect(service.get).toHaveBeenCalledWith('p-1');
    expect(cmp.resubmitting()).toBeTrue();
    expect(cmp.evidence().business.legalName).toBe('Pharmacie du Marché SARL');
    expect(cmp.canSubmit()).toBeTrue();

    cmp.submit();

    expect(service.resubmit.calls.mostRecent().args[0]).toBe('p-1');
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/super-admin/providers', 'p-1']);
  });

  it('resubmit of an unknown provider shows the calm not-found state', () => {
    service.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    const { cmp, host } = setup('missing');
    expect(cmp.notFound()).toBeTrue();
    expect(host.querySelector('[data-test="not-found"]')).not.toBeNull();
  });

  it('a failed load is not a not-found', () => {
    service.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const { cmp } = setup('p-1');
    expect(cmp.loadFailed()).toBeTrue();
    expect(cmp.notFound()).toBeFalse();
  });
});

describe('ProviderEvidenceFormComponent', () => {
  it('edits each layer immutably and labels the professional layer by facility type', () => {
    TestBed.configureTestingModule({
      imports: [ProviderEvidenceFormComponent, TranslateModule.forRoot()],
    });
    const fixture = TestBed.createComponent(ProviderEvidenceFormComponent);
    const before = evidenceOf(providerFixture());
    fixture.componentRef.setInput('evidence', before);
    fixture.componentRef.setInput('facilityType', 'LABORATORY');
    fixture.componentRef.setInput('idPrefix', 'x');
    fixture.detectChanges();
    const cmp = fixture.componentInstance;

    cmp.setBusiness('legalName', 'New SARL');
    cmp.setAddress('city', 'Bobo-Dioulasso');
    cmp.setProfessional('licenceNumber', 'AUT-9');

    const after = cmp.evidence();
    expect(after.business.legalName).toBe('New SARL');
    expect(after.business.address.city).toBe('Bobo-Dioulasso');
    expect(after.professional.licenceNumber).toBe('AUT-9');
    expect(before.business.legalName).toBe('Pharmacie du Marché SARL');
    expect(cmp.isLaboratory()).toBeTrue();
    expect(cmp.id('b-legalName')).toBe('x-b-legalName');
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('#x-b-legalName')).not.toBeNull();
    // Every input carries a label (axe).
    host.querySelectorAll('input').forEach((input) => {
      expect(host.querySelector(`label[for="${input.id}"]`))
        .withContext(input.id)
        .not.toBeNull();
    });
    TestBed.resetTestingModule();
  });
});
