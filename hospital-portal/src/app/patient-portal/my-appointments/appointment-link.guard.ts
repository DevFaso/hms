import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, UrlTree } from '@angular/router';

/**
 * Landing point for the appointment links in confirmation emails.
 *
 * <p>`AppointmentServiceImpl` builds those links as
 * `frontendBaseUrl + reschedule-path + appointmentId`, and the configured
 * fragments are `/appointments/reschedule/` and `/appointments/cancel/` —
 * paths that had **no route in this application at all**. Every such link
 * ever emailed landed on the wildcard route. The links were pointed at a
 * dev host until 2026-08-25, which is why nobody hit the 404 first.
 *
 * <p>Fixed here rather than by repointing the config at
 * `/my-appointments?cancel=`, because `UrlPathNormalizer.fragment` pins
 * every configured fragment to a trailing slash — a query-string value
 * would come out as `?cancel=/` and break the id concatenation. The
 * normaliser is right; the missing routes were the defect.
 *
 * <p>A guard rather than a component because there is nothing to render:
 * this only translates a public URL shape into the internal one the
 * appointments view understands.
 */
export const AppointmentLinkGuard: CanActivateFn = (route: ActivatedRouteSnapshot): UrlTree => {
  const router = inject(Router);

  const id = route.paramMap.get('id');
  const action = route.data['action'] as 'cancel' | 'reschedule';

  // No id means a truncated or mangled link. Send them to their
  // appointments rather than to an error: the list is what they wanted,
  // and they can act on the right row from there.
  if (!id) {
    return router.createUrlTree(['/my-appointments']);
  }

  return router.createUrlTree(['/my-appointments'], { queryParams: { [action]: id } });
};
