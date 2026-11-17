import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { Dhis2AdminPageComponent } from './dhis2-admin-page.component';
import { Dhis2Service } from '../../../services/integrations/dhis2.service';
import { Dhis2FacilityConfig } from '../../../services/integrations/dhis2.model';
import { RoleContextService } from '../../../core/role-context.service';
import { ToastService } from '../../../core/toast.service';

describe('Dhis2AdminPageComponent', () => {
  let fixture: ComponentFixture<Dhis2AdminPageComponent>;
  let dhis2: jasmine.SpyObj<Dhis2Service>;
  let toast: jasmine.SpyObj<ToastService>;

  const savedConfig = {
    hospitalId: 'h1',
    baseUrl: 'https://dhis2.example.org',
    authMode: 'PAT',
    authSecretEnvVar: 'DHIS2_PAT',
    defaultPeriodType: 'MONTHLY',
    defaultDatasetUid: null,
    active: true,
    lastExportAt: null,
    authSecretConfigured: false,
  } as unknown as Dhis2FacilityConfig;

  interface Exposed {
    configForm: { baseUrl: string; authSecretEnvVar: string };
    onSaveConfig: () => void;
    saveError: () => string | null;
  }

  beforeEach(() => {
    dhis2 = jasmine.createSpyObj('Dhis2Service', ['getFacilityConfig', 'upsertFacilityConfig']);
    dhis2.getFacilityConfig.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [Dhis2AdminPageComponent, TranslateModule.forRoot()],
      providers: [
        { provide: Dhis2Service, useValue: dhis2 },
        {
          provide: RoleContextService,
          useValue: jasmine.createSpyObj('RoleContextService', [], {
            activeHospitalId: 'h1',
            activeHospitalIdSignal: () => 'h1',
          }),
        },
        { provide: ToastService, useValue: toast },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      DHIS2: { PERIOD: { MONTHLY: 'Mensuel', WEEKLY: 'Hebdomadaire', YEARLY: 'Annuel' } },
    });
    translate.use('fr');
    fixture = TestBed.createComponent(Dhis2AdminPageComponent);
    fixture.detectChanges();
  });

  it('QA D875: the default period is offered in words, not as MONTHLY/WEEKLY/YEARLY', () => {
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="dhis2-config-period-type"] option',
      ),
    ).map((o) => (o.textContent ?? '').trim());

    expect(options).toEqual(['Mensuel', 'Hebdomadaire', 'Annuel']);
  });

  it('QA D875: a refused save shows the server field reason, not the generic text', () => {
    const reason = "L'URL de base doit commencer par http:// ou https://";
    dhis2.upsertFacilityConfig.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Validation Failed', fieldErrors: { baseUrl: reason } },
          }),
      ),
    );
    const c = fixture.componentInstance as unknown as Exposed;
    c.configForm.baseUrl = 'ftp://pas-une-url';
    c.configForm.authSecretEnvVar = 'DHIS2_PAT';
    c.onSaveConfig();

    expect(c.saveError()).toBe(reason);
  });

  it('QA D875: the error from a refused save is gone after the next save succeeds', () => {
    dhis2.upsertFacilityConfig.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'refusée' } })),
    );
    const c = fixture.componentInstance as unknown as Exposed;
    c.configForm.baseUrl = 'ftp://pas-une-url';
    c.configForm.authSecretEnvVar = 'DHIS2_PAT';
    c.onSaveConfig();
    expect(c.saveError()).toBe('refusée');

    dhis2.upsertFacilityConfig.and.returnValue(of(savedConfig));
    c.configForm.baseUrl = 'https://dhis2.example.org';
    c.onSaveConfig();

    expect(c.saveError()).toBeNull();
    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledTimes(1);
  });
});
