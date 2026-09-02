import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { StaffDetailComponent } from './staff-detail';
import { StaffResponse, StaffService } from '../services/staff.service';
import { StaffSchedulingService } from '../services/staff-scheduling.service';
import { CredentialingService } from '../services/credentialing.service';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';

/**
 * Staff detail — the general credentialing entry point (Tier 2 item 40).
 *
 * These exist because the dialog was previously reachable only from a
 * licence-expiry alert, and that list is built by a query requiring a
 * non-null expiry inside a cutoff. Clinicians here are credentialed on a
 * diploma, which has no expiry, so the very people V145 made the form work
 * for could never open it — and recording a diploma removed the practitioner
 * from the only list that could. The first test below is the one that fails
 * if that regresses.
 */
describe('StaffDetailComponent — credentialing entry point', () => {
  let fixture: ComponentFixture<StaffDetailComponent>;
  let staffService: jasmine.SpyObj<StaffService>;
  let auth: jasmine.SpyObj<AuthService>;

  function staff(overrides: Partial<StaffResponse> = {}): StaffResponse {
    return {
      id: 'staff-1',
      userId: 'user-1',
      username: 'atraore',
      name: 'Dr Awa Traore',
      email: 'awa@example.test',
      hospitalId: 'hosp-1',
      licenseNumber: 'MED-1234',
      // The default case in this deployment: a diploma, so no expiry at all.
      licenseExpiryDate: undefined,
      active: true,
      createdAt: '2026-01-05T08:00:00',
      ...overrides,
    };
  }

  async function setup(
    member: StaffResponse = staff(),
    roles: string[] = ['ROLE_HOSPITAL_ADMIN'],
  ): Promise<void> {
    staffService = jasmine.createSpyObj<StaffService>('StaffService', ['getById']);
    staffService.getById.and.returnValue(of(member));

    const scheduling = jasmine.createSpyObj<StaffSchedulingService>('StaffSchedulingService', [
      'listShifts',
      'listLeaves',
    ]);
    scheduling.listShifts.and.returnValue(of([]));
    scheduling.listLeaves.and.returnValue(of([]));

    const credentialing = jasmine.createSpyObj<CredentialingService>('CredentialingService', [
      'recordRenewal',
      'history',
    ]);
    credentialing.history.and.returnValue(of([]));

    auth = jasmine.createSpyObj<AuthService>('AuthService', ['hasAnyRole']);
    auth.hasAnyRole.and.callFake((expected: string[]) => expected.some((r) => roles.includes(r)));

    await TestBed.configureTestingModule({
      imports: [StaffDetailComponent, TranslateModule.forRoot()],
      providers: [
        { provide: StaffService, useValue: staffService },
        { provide: StaffSchedulingService, useValue: scheduling },
        { provide: CredentialingService, useValue: credentialing },
        { provide: AuthService, useValue: auth },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => 'staff-1' } } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StaffDetailComponent);
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function openEmploymentTab(): void {
    fixture.componentInstance.setTab('employment');
    fixture.detectChanges();
  }

  it('offers credentialing for a practitioner with no expiry on file', async () => {
    await setup();
    openEmploymentTab();

    const button = root().querySelector('[data-testid="open-credentialing"]');
    expect(button)
      .withContext(
        'a diploma holder is never in the expiry-alert list, so this is their only way in',
      )
      .not.toBeNull();
  });

  it('reads a missing expiry as "does not expire" rather than blank', async () => {
    await setup();
    openEmploymentTab();

    const cell = root().querySelector('[data-testid="staff-license-expiry"]');
    expect(cell?.textContent?.trim()).toBe('CREDENTIALING.NO_EXPIRY');
  });

  it('opens the dialog on that practitioner, carrying the null expiry through', async () => {
    await setup();
    openEmploymentTab();

    (root().querySelector('[data-testid="open-credentialing"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.credentialTarget()).toEqual({
      staffId: 'staff-1',
      staffName: 'Dr Awa Traore',
      licenseNumber: 'MED-1234',
      licenseExpiryDate: null,
    });
  });

  it('still shows a real expiry as a date', async () => {
    await setup(staff({ licenseExpiryDate: '2027-09-30' }));
    openEmploymentTab();

    const cell = root().querySelector('[data-testid="staff-license-expiry"]');
    expect(cell?.textContent?.trim()).not.toBe('CREDENTIALING.NO_EXPIRY');
    expect(cell?.textContent).toContain('2027');
  });

  it('hides the button from roles the credentialing endpoint would reject', async () => {
    // The route guard admits receptionists and lab managers; the controller's
    // @PreAuthorize does not. A visible button would only buy them a 403.
    await setup(staff(), ['ROLE_RECEPTIONIST']);
    openEmploymentTab();

    expect(root().querySelector('[data-testid="open-credentialing"]')).toBeNull();
  });

  it('refetches the staff member after a recording, since the expiry it shows just changed', async () => {
    await setup();
    openEmploymentTab();
    staffService.getById.calls.reset();

    fixture.componentInstance.onCredentialRecorded();

    expect(staffService.getById).toHaveBeenCalledWith('staff-1');
    expect(fixture.componentInstance.credentialTarget()).toBeNull();
  });
});
