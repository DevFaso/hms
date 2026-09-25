import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';

import { PatientDetailComponent } from './patient-detail';
import { RoleContextService } from '../core/role-context.service';

/**
 * The Documents tab is the only place on the patient chart that mounts a
 * hospital scope chip, and it got both of these wrong at once:
 *
 *  - without `preserveScope` the chip's `ngOnInit` ran `applyUrlScopeSync`,
 *    `/patients/:id` carries no `?hospitalId=`, and the chip answered by
 *    calling `enableGlobalView()` — so merely opening the tab dropped a pin
 *    made elsewhere, and the Labs tab then reported no hospital in scope;
 *  - `documentsScope` was a plain signal seeded null, and
 *    `DocumentsTabComponent` reloads only when that input changes, so the
 *    chip's "All hospitals" over a pinned session emitted null, matched the
 *    seed and reloaded nothing.
 *
 * Neither had a test, and both are one template edit away from returning.
 */
describe('PatientDetailComponent — documents scope', () => {
  function roleContext(): RoleContextService {
    const ctx = TestBed.inject(RoleContextService);
    ctx.setRoles(['ROLE_SUPER_ADMIN']);
    ctx.activeHospitalId = 'h-primary';
    return ctx;
  }

  it('tracks the live scope rather than a value captured once', () => {
    TestBed.configureTestingModule({
      imports: [TranslateModule.forRoot()],
      providers: [provideRouter([]), provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    const ctx = roleContext();
    ctx.scopeToHospital('h-b');

    const documentsScope = TestBed.runInInjectionContext(
      () => new PatientDetailComponent().documentsScope,
    );
    expect(documentsScope()).toBe('h-b');

    // Anything can move the scope while the chart is open — the chip itself,
    // a role switch, a reset. The input has to follow, or the list and the
    // chip disagree.
    ctx.scopeToHospital('h-c');
    expect(documentsScope()).toBe('h-c');

    ctx.enableGlobalView();
    expect(documentsScope()).toBeNull();
  });

  it('is null only when the session really has no scope', () => {
    TestBed.configureTestingModule({
      imports: [TranslateModule.forRoot()],
      providers: [provideRouter([]), provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    const ctx = TestBed.inject(RoleContextService);
    ctx.setRoles(['ROLE_DOCTOR']);
    ctx.activeHospitalId = 'h-1';

    const documentsScope = TestBed.runInInjectionContext(
      () => new PatientDetailComponent().documentsScope,
    );
    expect(documentsScope()).toBe('h-1');
  });
});
