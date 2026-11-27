/**
 * The agreed WCAG 2.1 AA scope (docs/qa/wcag-scope.md §3): the pages of the
 * main journeys, each scanned as the role that works on it. The scope
 * document and this list are kept identical by
 * scripts/wcag-journey-scope.test.mjs — add a page to both or to neither.
 */
export interface WcagJourneyPage {
  /** Journey identifier, as written in docs/qa/wcag-scope.md §3. */
  journey: string;
  /** Route as declared in src/app/app.routes.ts. */
  route: string;
  /** Working role; null for a public page scanned signed out. */
  role: string | null;
  /**
   * A page that cannot be opened by its address: it is reached by signing in
   * on /login against a mocked answer (a second factor is due, enrolment is
   * due, a temporary password must be replaced).
   */
  viaLogin?: 'mfa-challenge' | 'mfa-enroll' | 'account-setup';
  /** Query string that puts the page in its working state (never a real token). */
  query?: string;
  /**
   * Structure rules this page still fails because the file to change belongs
   * to an open pull request. Shrinks only: remove the entry when `owner` merges.
   */
  pending?: { rules: string[]; owner: string };
  /** The button that opens the page's entry dialog, when its journey starts in one. */
  opener?: string;
}

/** The entry dialogs' opener: the page header's primary action. */
const CREATE = '.page-header .btn-primary';

export const WCAG_WIDTHS = [390, 768, 1440] as const;

export const WCAG_JOURNEY_PAGES: readonly WcagJourneyPage[] = [
  // J1 — sign-in, second factor, account setup
  { journey: 'J1', route: '/login', role: null },
  { journey: 'J1', route: '/mfa-challenge', role: null, viaLogin: 'mfa-challenge' },
  { journey: 'J1', route: '/mfa-enroll', role: null, viaLogin: 'mfa-enroll' },
  { journey: 'J1', route: '/reset-password', role: null, query: '?token=cdx-a11y-fixture' },
  { journey: 'J1', route: '/verify', role: null },
  {
    journey: 'J1',
    route: '/account-setup',
    role: null,
    viaLogin: 'account-setup',
    // The page has no <main>; account-setup.html is being edited by #1345.
    pending: { rules: ['landmark-one-main', 'region'], owner: '#1345' },
  },
  // J2 — reception: registration, booking, check-in
  { journey: 'J2', route: '/reception', role: 'ROLE_RECEPTIONIST' },
  { journey: 'J2', route: '/patients', role: 'ROLE_RECEPTIONIST' },
  { journey: 'J2', route: '/patients/new', role: 'ROLE_RECEPTIONIST' },
  { journey: 'J2', route: '/appointments', role: 'ROLE_RECEPTIONIST' },
  { journey: 'J2', route: '/appointments/new', role: 'ROLE_RECEPTIONIST' },
  { journey: 'J2', route: '/appointments/calendar', role: 'ROLE_RECEPTIONIST' },
  { journey: 'J2', route: '/registrations', role: 'ROLE_RECEPTIONIST', opener: CREATE },
  // J3 — triage and vitals
  { journey: 'J3', route: '/nurse-station', role: 'ROLE_NURSE' },
  { journey: 'J3', route: '/patient-tracker', role: 'ROLE_NURSE' },
  // J4 — consultation note and prescription
  { journey: 'J4', route: '/consultations', role: 'ROLE_DOCTOR', opener: CREATE },
  { journey: 'J4', route: '/encounters', role: 'ROLE_DOCTOR', opener: CREATE },
  { journey: 'J4', route: '/prescriptions', role: 'ROLE_DOCTOR', opener: CREATE },
  // J5 — lab order to result release
  { journey: 'J5', route: '/lab', role: 'ROLE_DOCTOR', opener: CREATE },
  { journey: 'J5', route: '/lab-results', role: 'ROLE_LAB_TECHNICIAN', opener: CREATE },
  { journey: 'J5', route: '/lab-approval-queue', role: 'ROLE_LAB_SCIENTIST' },
  { journey: 'J5', route: '/lab-release-worklist', role: 'ROLE_LAB_SCIENTIST' },
  // J6 — pharmacy dispense and hand-over
  { journey: 'J6', route: '/pharmacy/dispensing', role: 'ROLE_PHARMACIST' },
  { journey: 'J6', route: '/pharmacy/checkout', role: 'ROLE_PHARMACIST' },
  { journey: 'J6', route: '/pharmacy/caisse', role: 'ROLE_PHARMACIST' },
  // J7 — billing: invoice, payment, till close
  { journey: 'J7', route: '/billing', role: 'ROLE_ACCOUNTANT' },
  { journey: 'J7', route: '/billing/caisses', role: 'ROLE_ACCOUNTANT' },
  // J8 — nurse medication round
  { journey: 'J8', route: '/emar', role: 'ROLE_NURSE' },
  // J9 — maternity: labour and delivery
  { journey: 'J9', route: '/maternity', role: 'ROLE_MIDWIFE', opener: CREATE },
  // J10 — patient portal records
  { journey: 'J10', route: '/my-records', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-visits', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-medications', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-lab-results', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-imaging', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-vitals', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-documents', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-appointments', role: 'ROLE_PATIENT' },
  { journey: 'J10', route: '/my-billing', role: 'ROLE_PATIENT' },
];
