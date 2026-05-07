import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { HospitalResponse } from '../../services/hospital.service';
import { HospitalScopeChipComponent } from './hospital-scope-chip.component';

/**
 * Unit tests for the cross-tenant scope chip
 * (docs/super-admin-cross-tenant-design.md).
 *
 * Exercise focus:
 *   - chip is invisible for non-super-admin users
 *   - chip renders "All hospitals" by default for super-admin
 *   - clicking a typeahead match flips RoleContextService scope and emits scopeChange
 *   - clicking the ✕ clears scope back to global view
 */
describe('HospitalScopeChipComponent', () => {
  let fixture: ComponentFixture<HospitalScopeChipComponent>;
  let component: HospitalScopeChipComponent;
  let roleContext: RoleContextService;

  function asSuperAdmin(): void {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.markSuperAdminGlobalDefaults();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HospitalScopeChipComponent, TranslateModule.forRoot()],
      // The chip injects ActivatedRoute + Router via HospitalScopeUrlService
      // for `?hospitalId=` URL round-trip, so provide a stub router.
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    fixture = TestBed.createComponent(HospitalScopeChipComponent);
    component = fixture.componentInstance;
    roleContext = TestBed.inject(RoleContextService);
  });

  it('renders nothing for non-super-admin users', () => {
    roleContext.setRoles(['ROLE_DOCTOR']);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="hospital-scope-chip"]'),
    ).toBeNull();
  });

  it('renders the "All hospitals" chip for a super-admin in global view', () => {
    asSuperAdmin();
    fixture.detectChanges();
    const chip = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[data-testid="hospital-scope-chip-button"]',
    );
    expect(chip).toBeTruthy();
    expect(chip!.textContent).toContain('HOSPITAL_SCOPE.ALL_HOSPITALS');
  });

  it('opens the typeahead overlay on chip click', () => {
    asSuperAdmin();
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLElement>('[data-testid="hospital-scope-chip-button"]')!
      .click();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="hospital-scope-chip-overlay"]',
      ),
    ).toBeTruthy();
  });

  it('onSelectHospital pins RoleContext + emits scopeChange + closes the overlay', () => {
    asSuperAdmin();
    fixture.detectChanges();

    const emissions: (string | null)[] = [];
    component.scopeChange.subscribe((id) => emissions.push(id));

    const hospital: HospitalResponse = {
      id: 'hosp-42',
      name: 'Memorial Hospital',
    } as HospitalResponse;
    component['onSelectHospital'](hospital);
    fixture.detectChanges();

    expect(roleContext.globalView()).toBeFalse();
    expect(roleContext.selectedHospitalId()).toBe('hosp-42');
    expect(emissions).toEqual(['hosp-42']);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="hospital-scope-chip-overlay"]',
      ),
    ).toBeNull();
    // Chip should now show the hospital name + a ✕ clear button.
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="hospital-scope-chip-name"]',
      )?.textContent,
    ).toContain('Memorial Hospital');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="hospital-scope-chip-clear"]',
      ),
    ).toBeTruthy();
  });

  it('clearScope returns to global view and emits null', () => {
    asSuperAdmin();
    // detectChanges first so the chip's ngOnInit runs (and applies URL
    // scope) BEFORE we mutate scope — otherwise ngOnInit would see no
    // `?hospitalId=` param and call enableGlobalView(), clobbering our
    // scopeToHospital(...) call.
    fixture.detectChanges();
    component['onSelectHospital']({ id: 'hosp-42', name: 'Memorial' } as HospitalResponse);
    fixture.detectChanges();

    const emissions: (string | null)[] = [];
    component.scopeChange.subscribe((id) => emissions.push(id));

    const clearBtn = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[data-testid="hospital-scope-chip-clear"]',
    );
    expect(clearBtn).toBeTruthy();
    clearBtn!.click();
    fixture.detectChanges();

    expect(roleContext.globalView()).toBeTrue();
    expect(roleContext.selectedHospitalId()).toBeNull();
    expect(emissions).toEqual([null]);
  });

  it('onSelectAll switches to global view and closes the overlay', () => {
    asSuperAdmin();
    fixture.detectChanges();
    component['onSelectHospital']({ id: 'hosp-42', name: 'Memorial' } as HospitalResponse);
    fixture.detectChanges();

    const emissions: (string | null)[] = [];
    component.scopeChange.subscribe((id) => emissions.push(id));

    component['onSelectAll']();
    fixture.detectChanges();

    expect(roleContext.globalView()).toBeTrue();
    expect(emissions).toEqual([null]);
  });
});
