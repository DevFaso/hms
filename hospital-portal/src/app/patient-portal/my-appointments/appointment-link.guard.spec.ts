import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { provideRouter } from '@angular/router';

import { AppointmentLinkGuard } from './appointment-link.guard';

/**
 * These two URL shapes are what appointment confirmation emails have always
 * contained. Until 2026-08-25 neither had a route at all, so the assertion
 * that matters is that the guard produces a destination the app can actually
 * render.
 */
describe('AppointmentLinkGuard', () => {
  let router: Router;

  const run = (id: string | null, action: 'cancel' | 'reschedule'): UrlTree => {
    const route = {
      paramMap: { get: (k: string) => (k === 'id' ? id : null) },
      data: { action },
    } as unknown as ActivatedRouteSnapshot;
    return TestBed.runInInjectionContext(
      () => AppointmentLinkGuard(route, {} as RouterStateSnapshot) as UrlTree,
    );
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    router = TestBed.inject(Router);
  });

  it('sends a cancel link to the appointments view with the cancel param', () => {
    expect(router.serializeUrl(run('appt-1', 'cancel'))).toBe('/my-appointments?cancel=appt-1');
  });

  it('sends a reschedule link to the appointments view with the reschedule param', () => {
    expect(router.serializeUrl(run('appt-2', 'reschedule'))).toBe(
      '/my-appointments?reschedule=appt-2',
    );
  });

  it('falls back to the plain list when the link is truncated', () => {
    // A mangled link should still land the patient somewhere useful — the
    // list is what they wanted, and they can act on the right row there.
    expect(router.serializeUrl(run(null, 'cancel'))).toBe('/my-appointments');
  });
});
