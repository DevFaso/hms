import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PacsSettingsComponent } from './pacs-settings.component';
import { ImagingPacsService, PacsSettings } from '../../services/imaging-pacs.service';
import { HospitalResponse, HospitalService } from '../../services/hospital.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

describe('PacsSettingsComponent', () => {
  let fixture: ComponentFixture<PacsSettingsComponent>;
  let pacs: jasmine.SpyObj<ImagingPacsService>;
  let toast: jasmine.SpyObj<ToastService>;

  const settings = (over: Partial<PacsSettings> = {}): PacsSettings => ({
    hospitalId: 'h1',
    enabled: false,
    dicomwebBaseUrl: null,
    viewerUrlTemplate: null,
    authUsername: null,
    passwordSet: false,
    state: 'NOT_CONFIGURED',
    effectiveSource: null,
    platformProxyEnabled: true,
    platformFallbackConfigured: false,
    privateHostsAllowed: false,
    ...over,
  });

  async function setup(initial: PacsSettings): Promise<void> {
    pacs = jasmine.createSpyObj<ImagingPacsService>('ImagingPacsService', [
      'settings',
      'saveSettings',
      'testConnection',
      'reportingSettings',
      'setReportingSettings',
    ]);
    // Policy rule 3: the page also hosts the imaging reporting settings block.
    pacs.reportingSettings.and.returnValue(
      of({ hospitalId: 'h1', ultrasoundByNonPhysician: false }),
    );
    pacs.settings.and.returnValue(of(initial));
    pacs.saveSettings.and.returnValue(of(initial));
    pacs.testConnection.and.returnValue(of({ result: 'OK', source: 'HOSPITAL' }));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    const hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitals.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'Clinique' } as HospitalResponse),
    );
    hospitals.list.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [PacsSettingsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ImagingPacsService, useValue: pacs },
        { provide: HospitalService, useValue: hospitals },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: { isSuperAdmin: signal(false) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PacsSettingsComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it("loads the hospital admin's own hospital and never shows a password", async () => {
    await setup(settings({ passwordSet: true, authUsername: 'orthanc' }));
    expect(pacs.settings).toHaveBeenCalledWith('h1');
    const password = fixture.nativeElement.querySelector(
      '[data-testid="pacs-settings-password"]',
    ) as HTMLInputElement;
    expect(password.value).toBe('');
    expect(password.placeholder).toBe('IMAGING_PACS.SETTINGS.PASSWORD_KEEP');
  });

  it('refuses switching the viewer on without an address, marking the field', async () => {
    await setup(settings({ enabled: true }));
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="pacs-settings-save"]',
      sent: pacs.saveSettings,
    });
  });

  it('sends a blank password as null (keep), and marks the fields the server refuses', async () => {
    await setup(settings());
    const cmp = fixture.componentInstance;
    cmp.form.enabled = true;
    cmp.form.dicomwebBaseUrl = 'http://10.0.0.5/dicom-web';
    pacs.saveSettings.and.returnValue(
      throwError(() => fieldErrorsResponse({ dicomwebBaseUrl: 'Adresse refusée' })),
    );
    cmp.save();
    fixture.detectChanges();
    expect(pacs.saveSettings).toHaveBeenCalledWith(
      'h1',
      jasmine.objectContaining({ authPassword: null, clearPassword: false }),
    );
    expectFieldsMarked(fixture, { 'pacs-base-url': 'Adresse refusée' });
    expect(toast.error).toHaveBeenCalled();
  });

  it('shows the connection test result', async () => {
    await setup(settings({ enabled: true, dicomwebBaseUrl: 'https://pacs.example/dicom-web' }));
    (
      fixture.nativeElement.querySelector('[data-testid="pacs-settings-test"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const result = fixture.nativeElement.querySelector(
      '[data-testid="pacs-settings-test-result"]',
    ) as HTMLElement;
    expect(result.textContent).toContain('IMAGING_PACS.SETTINGS.TEST_RESULT.OK');
  });
});
