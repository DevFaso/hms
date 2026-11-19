import type { Page } from '@playwright/test';

export const TEST_ID = '00000000-0000-0000-0000-00000000cd12';

/**
 * Sign the SPA in as one role against the mocked backend (same token and
 * session-bootstrap shape the routed a11y inventory uses), and block every
 * request that leaves the dev server so no test reaches a real provider.
 */
export async function signInAs(page: Page, role: string, baseURL: string): Promise<void> {
  const origin = new URL(baseURL).origin;
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    return url.origin === origin ? route.fallback() : route.abort('blockedbyclient');
  });
  const profile = {
    id: TEST_ID,
    username: 'layout-guard',
    email: 'layout@example.invalid',
    active: true,
    roles: [role],
    primaryHospitalId: TEST_ID,
    primaryHospitalName: 'Centre Hospitalier Wend-Panga',
    hospitalIds: [TEST_ID],
  };
  await page.addInitScript(
    ({ profile }) => {
      const payload = btoa(
        JSON.stringify({ sub: profile.username, roles: profile.roles, exp: 9999999999 }),
      );
      localStorage.setItem('auth_token', `eyJhbGciOiJIUzI1NiJ9.${payload}.layout-signature`);
      localStorage.setItem('user_profile', JSON.stringify(profile));
    },
    { profile },
  );
  await page.route('**/api/auth/session/bootstrap', (route) =>
    route.fulfill({
      json: {
        ...profile,
        userId: TEST_ID,
        authSource: 'internal',
        superAdmin: role === 'ROLE_SUPER_ADMIN',
        hospitalAdmin: role === 'ROLE_HOSPITAL_ADMIN',
        primaryHospitalId: TEST_ID,
        permittedHospitalIds: [TEST_ID],
        staffId: TEST_ID,
        patientId: TEST_ID,
      },
    }),
  );
}
