import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { signal } from '@angular/core';
import { of } from 'rxjs';

import { PrescriptionsComponent } from './prescriptions';
import { PrescriptionService, CommunityPharmacyService } from '../services/prescription.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';

/**
 * Regression guard for the "Send prescription by SMS" dialog.
 *
 * The dispatch modal shipped as a SIBLING of its own `.modal-backdrop`
 * instead of a child. Because the backdrop is `position: fixed; z-index: 1000`
 * with `backdrop-filter: blur(4px)` and `.modal` carries no position or
 * z-index, the backdrop painted on top of the dialog — blurring it along with
 * the page, dropping it out of the backdrop's flex centring, and swallowing
 * every click (the backdrop's own handler closes the modal).
 */
describe('PrescriptionsComponent — SMS dispatch modal', () => {
  let fixture: ComponentFixture<PrescriptionsComponent>;
  let component: PrescriptionsComponent;

  beforeEach(async () => {
    const prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
    ]);
    prescriptionService.list.and.returnValue(of([]));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));

    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));

    const communityPharmacyService = jasmine.createSpyObj<CommunityPharmacyService>(
      'CommunityPharmacyService',
      ['list'],
    );
    communityPharmacyService.list.and.returnValue(of([]));

    const scopeUrl = jasmine.createSpyObj<HospitalScopeUrlService>('HospitalScopeUrlService', [
      'applyUrlScopeSync',
    ]);

    await TestBed.configureTestingModule({
      imports: [PrescriptionsComponent, TranslateModule.forRoot()],
      providers: [
        // The scope chip pulls in HospitalService, which needs HttpClient.
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PrescriptionService, useValue: prescriptionService },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: patientService },
        { provide: CommunityPharmacyService, useValue: communityPharmacyService },
        { provide: HospitalScopeUrlService, useValue: scopeUrl },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            hasAnyActiveRole: () => true,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrescriptionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function openDispatch(): void {
    component.showDispatchModal.set(true);
    fixture.detectChanges();
  }

  it('does not render the dispatch modal until it is opened', () => {
    expect(fixture.nativeElement.querySelector('[data-testid="rx-dispatch-modal"]')).toBeNull();
  });

  it('renders the dialog INSIDE its backdrop, not as a sibling', () => {
    openDispatch();

    const backdrop = fixture.nativeElement.querySelector(
      '[data-testid="rx-dispatch-modal"]',
    ) as HTMLElement;
    expect(backdrop).not.toBeNull();

    const dialog = backdrop.querySelector('.modal');
    expect(dialog)
      .withContext(
        '.modal must be a descendant of .modal-backdrop, or the blurred backdrop paints over it',
      )
      .not.toBeNull();
  });

  it('keeps clicks inside the dialog from reaching the backdrop and closing it', () => {
    openDispatch();

    const dialog = fixture.nativeElement.querySelector(
      '[data-testid="rx-dispatch-modal"] .modal',
    ) as HTMLElement;
    dialog.click();
    fixture.detectChanges();

    expect(component.showDispatchModal()).toBeTrue();
  });

  it('closes when the backdrop itself is clicked', () => {
    openDispatch();

    const backdrop = fixture.nativeElement.querySelector(
      '[data-testid="rx-dispatch-modal"]',
    ) as HTMLElement;
    backdrop.click();
    fixture.detectChanges();

    expect(component.showDispatchModal()).toBeFalse();
  });
});
