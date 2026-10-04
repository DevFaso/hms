/**
 * B7 — who may read the lab-result review queue.
 *
 * Mirrors `MeController#getResultReviewQueue`'s
 * `@PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")`
 * on `GET /me/results/review-queue`, which has no SecurityConfig matcher of
 * its own, so that annotation is the whole gate.
 *
 * The three names are listed literally so the list reads exactly as the
 * annotation does. (`RoleContextService.hasAnyActiveRole` now applies doctor
 * equivalence itself, so `['ROLE_DOCTOR']` would also admit a surgeon; the
 * literal list predates that and stays as the mirror.)
 *
 * The /in-basket route itself is wider — it also admits nurses, midwives and
 * two lab roles — so this category is gated separately inside the page: the
 * route cannot be narrowed to doctors without taking the rest of the in-basket
 * away from everyone else.
 */
export const LAB_RESULT_REVIEW_ROLES: string[] = ['ROLE_DOCTOR', 'ROLE_PHYSICIAN', 'ROLE_SURGEON'];
