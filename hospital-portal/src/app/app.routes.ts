import { Routes } from '@angular/router';
import { AuthGuard } from './auth/auth.guard';
import { AccountSetupGuard } from './auth/account-setup.guard';
import { LoginRedirectGuard } from './auth/login-redirect.guard';
import { RoleGuard } from './auth/role.guard';
import { SuperAdminRedirectGuard } from './auth/super-admin-redirect.guard';
import { superAdminPathRewriteGuard } from './auth/super-admin-path-rewrite.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  // Public
  {
    path: 'login',
    canActivate: [LoginRedirectGuard],
    loadComponent: () => import('./login/login').then((m) => m.Login),
  },
  {
    path: 'mfa-challenge',
    loadComponent: () => import('./mfa/mfa-challenge').then((m) => m.MfaChallengeComponent),
  },
  {
    path: 'mfa-enroll',
    loadComponent: () => import('./mfa/mfa-enroll').then((m) => m.MfaEnrollComponent),
  },
  {
    path: 'reset-password',
    loadComponent: () =>
      import('./reset-password/reset-password').then((m) => m.ResetPasswordComponent),
  },
  {
    path: 'privacy-policy',
    loadComponent: () =>
      import('./privacy-policy/privacy-policy').then((m) => m.PrivacyPolicyComponent),
  },
  {
    path: 'onboarding/role-welcome',
    loadComponent: () =>
      import('./onboarding/role-welcome/role-welcome').then((m) => m.RoleWelcomeComponent),
  },

  // First-login credential setup (authenticated but not yet through account-setup guard)
  {
    path: 'account-setup',
    canActivate: [AuthGuard],
    loadComponent: () =>
      import('./account-setup/account-setup').then((m) => m.AccountSetupComponent),
  },

  // Authenticated shell
  {
    path: '',
    canActivate: [AuthGuard, AccountSetupGuard],
    loadComponent: () => import('./shell/shell').then((m) => m.ShellComponent),
    children: [
      {
        path: 'dashboard',
        canActivate: [SuperAdminRedirectGuard],
        loadComponent: () => import('./dashboard/dashboard').then((m) => m.DashboardComponent),
      },

      // ─── Patient Portal (ROLE_PATIENT only) ───────────────────
      {
        path: 'my-appointments',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-appointments/my-appointments.component').then(
            (m) => m.MyAppointmentsComponent,
          ),
      },
      {
        path: 'my-education',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-education/my-education.component').then(
            (m) => m.MyEducationComponent,
          ),
      },
      {
        path: 'my-medications',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-medications/my-medications.component').then(
            (m) => m.MyMedicationsComponent,
          ),
      },
      {
        path: 'my-lab-results',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-lab-results/my-lab-results.component').then(
            (m) => m.MyLabResultsComponent,
          ),
      },
      {
        path: 'my-vitals',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-vitals/my-vitals.component').then((m) => m.MyVitalsComponent),
      },
      {
        path: 'my-billing',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-billing/my-billing.component').then(
            (m) => m.MyBillingComponent,
          ),
      },
      {
        // T-45: patient portal pharmacy invoice & payment history
        path: 'my-pharmacy-invoices',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-pharmacy-invoices/my-pharmacy-invoices.component').then(
            (m) => m.MyPharmacyInvoicesComponent,
          ),
      },
      {
        path: 'my-visits',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-visits/my-visits.component').then((m) => m.MyVisitsComponent),
      },
      {
        path: 'my-care-team',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-care-team/my-care-team.component').then(
            (m) => m.MyCareTeamComponent,
          ),
      },
      {
        path: 'my-records',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-records/my-records.component').then(
            (m) => m.MyRecordsComponent,
          ),
      },
      {
        path: 'my-medical-history',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-medical-history/my-medical-history.component').then(
            (m) => m.MyMedicalHistoryComponent,
          ),
      },
      {
        path: 'my-sharing',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-sharing/my-sharing.component').then(
            (m) => m.MySharingComponent,
          ),
      },
      {
        path: 'my-family-access',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-family-access/my-family-access.component').then(
            (m) => m.MyFamilyAccessComponent,
          ),
      },
      {
        path: 'my-family-access/:patientId',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-family-access/proxy-data-viewer/proxy-data-viewer.component').then(
            (m) => m.ProxyDataViewerComponent,
          ),
      },
      {
        path: 'my-summaries',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-summaries/my-summaries.component').then(
            (m) => m.MySummariesComponent,
          ),
      },
      {
        path: 'my-documents',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-documents/my-documents.component').then(
            (m) => m.MyDocumentsComponent,
          ),
      },
      {
        path: 'my-notifications',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-notifications/my-notifications.component').then(
            (m) => m.MyNotificationsComponent,
          ),
      },

      // Patients
      {
        path: 'patients',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_RECEPTIONIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_SCIENTIST',
            'ROLE_QUALITY_MANAGER',
          ],
        },
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./patients/patient-list').then((m) => m.PatientListComponent),
          },
          {
            path: 'new',
            loadComponent: () =>
              import('./patients/patient-form').then((m) => m.PatientFormComponent),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./patients/patient-detail').then((m) => m.PatientDetailComponent),
          },
        ],
      },

      // Appointments
      {
        path: 'appointments',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_RECEPTIONIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./appointments/appointment-list').then((m) => m.AppointmentListComponent),
          },
          {
            path: 'new',
            loadComponent: () =>
              import('./appointments/appointment-form').then((m) => m.AppointmentFormComponent),
          },
          {
            // Cadence visual scheduling grid (P1 #7) — lazy so FullCalendar
            // only ships when this route is visited.
            path: 'calendar',
            loadComponent: () =>
              import('./appointments/calendar/appointment-calendar.component').then(
                (m) => m.AppointmentCalendarComponent,
              ),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./appointments/appointment-detail').then((m) => m.AppointmentDetailComponent),
          },
        ],
      },

      // Staff
      {
        path: 'staff',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_RECEPTIONIST',
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
          ],
        },
        children: [
          {
            path: '',
            loadComponent: () => import('./staff/staff-list').then((m) => m.StaffListComponent),
          },
          {
            path: ':id',
            loadComponent: () => import('./staff/staff-detail').then((m) => m.StaffDetailComponent),
          },
        ],
      },

      // Staff Scheduling
      {
        path: 'scheduling',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_RECEPTIONIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_SCIENTIST',
            'ROLE_QUALITY_MANAGER',
          ],
        },
        loadComponent: () => import('./scheduling/scheduling').then((m) => m.SchedulingComponent),
      },

      // On-call rota (P2 #13). Own top-level route, NOT a /scheduling child:
      // doctorHiddenRoutes hides /scheduling from doctors, who are readers
      // here. Roles mirror OnCallScheduleController.READ_ROLES exactly (no
      // ROLE_ADMIN — the backend does not grant it); write controls are gated
      // in-component to the narrower WRITE_ROLES.
      {
        path: 'on-call',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_RECEPTIONIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./on-call/on-call').then((m) => m.OnCallComponent),
      },

      // Departments
      {
        path: 'departments',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_RECEPTIONIST',
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_SCIENTIST',
            'ROLE_QUALITY_MANAGER',
          ],
        },
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./departments/department-list').then((m) => m.DepartmentListComponent),
          },
          {
            path: ':id',
            loadComponent: () =>
              import('./departments/department-detail').then((m) => m.DepartmentDetailComponent),
          },
        ],
      },

      // Billing
      {
        path: 'billing',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_SUPER_ADMIN',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_BILLING_SPECIALIST',
            'ROLE_ACCOUNTANT',
            'ROLE_RECEPTIONIST',
          ],
        },
        loadComponent: () => import('./billing/billing').then((m) => m.BillingComponent),
      },

      // Laboratory
      {
        path: 'lab',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_LAB_TECHNICIAN',
            'ROLE_LAB_SCIENTIST',
            'ROLE_LAB_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
          ],
        },
        loadComponent: () => import('./lab/lab').then((m) => m.LabComponent),
      },
      {
        path: 'lab-results',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_LAB_TECHNICIAN',
            'ROLE_LAB_SCIENTIST',
            'ROLE_LAB_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-results/lab-results').then((m) => m.LabResultsComponent),
      },
      {
        // Roles mirror MicroCultureController.READ_ROLES exactly (P3 #19) —
        // resulting/finalize buttons are gated in-component to the narrower
        // ENTRY/FINALIZE role sets.
        path: 'microbiology',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_SCIENTIST',
            'ROLE_LAB_TECHNICIAN',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_PHARMACIST',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./micro/microbiology').then((m) => m.MicrobiologyComponent),
      },
      {
        path: 'lab-approval-queue',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_SCIENTIST',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-approval-queue/lab-approval-queue').then(
            (m) => m.LabApprovalQueueComponent,
          ),
      },
      {
        path: 'lab-qc-dashboard',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-qc-dashboard/lab-qc-dashboard').then((m) => m.LabQcDashboardComponent),
      },
      {
        path: 'lab-ops-dashboard',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_QUALITY_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-ops-dashboard/lab-ops-dashboard').then(
            (m) => m.LabOpsDashboardComponent,
          ),
      },
      {
        path: 'lab-staff',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-staff-list/lab-staff-list').then((m) => m.LabStaffListComponent),
      },
      {
        path: 'lab-test-config',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_SCIENTIST',
            'ROLE_QUALITY_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-test-config/lab-test-config').then((m) => m.LabTestConfigComponent),
      },
      {
        path: 'lab-instruments',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_TECHNICIAN',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-instruments/lab-instruments').then((m) => m.LabInstrumentsComponent),
      },
      // Instrument outbox monitor (P2 #17) — roles mirror the backend READ set
      // on GET /lab-instrument-outbox/** exactly.
      {
        path: 'lab-outbox',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_TECHNICIAN',
            'ROLE_LAB_SCIENTIST',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-outbox/lab-outbox').then((m) => m.LabOutboxComponent),
      },
      {
        path: 'lab-inventory',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_LAB_DIRECTOR',
            'ROLE_LAB_MANAGER',
            'ROLE_LAB_TECHNICIAN',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./lab/lab-inventory/lab-inventory').then((m) => m.LabInventoryComponent),
      },

      // Profile (all authenticated users)
      {
        path: 'profile',
        loadComponent: () => import('./profile/profile').then((m) => m.ProfileComponent),
      },

      // Settings hub — preferences, language, shortcuts to security/notification
      // pages. Replaced the old /settings → /profile redirect because both
      // header menu items pointed at the same screen, which surprised users.
      {
        path: 'settings',
        loadComponent: () => import('./settings/settings').then((m) => m.SettingsComponent),
      },

      // Notifications
      {
        path: 'notifications',
        loadComponent: () =>
          import('./notifications/notification-list').then((m) => m.NotificationListComponent),
      },
      {
        path: 'notification-settings',
        loadComponent: () =>
          import('./notifications/notification-settings').then(
            (m) => m.NotificationSettingsComponent,
          ),
      },

      // Chat / Messages
      {
        path: 'chat',
        loadComponent: () => import('./chat/chat').then((m) => m.ChatComponent),
      },

      // Announcements (all authenticated users can view; admins can also manage)
      {
        path: 'announcements',
        loadComponent: () =>
          import('./announcements/announcement-list').then((m) => m.AnnouncementListComponent),
      },

      // Hospitals (Admin)
      {
        path: 'hospitals',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_SUPER_ADMIN',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_RECEPTIONIST',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
          ],
        },
        loadComponent: () =>
          import('./hospitals/hospital-list').then((m) => m.HospitalListComponent),
      },

      // Hospital detail with Lifecycle panel (MVP-c2-frontend — see
      // docs/super-admin-gaps.md). Super-admin-only because the
      // panel can suspend / archive / schedule purge.
      {
        path: 'hospitals/:id',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./hospitals/hospital-detail').then((m) => m.HospitalDetailComponent),
      },

      // Organizations (Admin)
      {
        path: 'organizations',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./organizations/organization-list').then((m) => m.OrganizationListComponent),
      },
      {
        path: 'organizations/:id',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./organizations/organization-detail').then((m) => m.OrganizationDetailComponent),
      },

      // Users (Admin)
      {
        path: 'users',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./users/user-list').then((m) => m.UserListComponent),
      },

      // Roles (Admin)
      {
        path: 'roles',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./roles/role-list').then((m) => m.RoleListComponent),
      },

      // Platform (Admin)
      // MVP-5b: SUPER_ADMIN is rewritten to /super-admin/platform for
      // namespace consistency; ADMIN keeps the legacy /platform path.
      {
        path: 'platform',
        canActivate: [superAdminPathRewriteGuard('/super-admin/platform'), RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./platform/platform').then((m) => m.PlatformComponent),
      },

      // Encounters
      {
        path: 'encounters',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./encounters/encounters').then((m) => m.EncountersComponent),
      },

      // Admissions
      {
        path: 'admissions',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./admissions/admissions').then((m) => m.AdmissionsComponent),
      },

      // Bed management (P0 #4 — ward/bed CRUD + occupancy)
      {
        path: 'bed-management',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./bed-management/bed-management').then((m) => m.BedManagementComponent),
      },

      // Slot inventory administration (P2 #11 — visit types, session
      // templates, generation). Backend writes are HOSPITAL_ADMIN/SUPER_ADMIN
      // (no ROLE_ADMIN), so the route matches that exactly.
      {
        path: 'slot-admin',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () => import('./slot-admin/slot-admin').then((m) => m.SlotAdminComponent),
      },

      // Scheduled reports (P3 #25a) — roles mirror ReportController's
      // class-level @PreAuthorize exactly (HOSPITAL_ADMIN / SUPER_ADMIN).
      {
        path: 'reports',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () => import('./reports/reports').then((m) => m.ReportsComponent),
      },

      // Discharge (approvals + summaries)
      {
        path: 'discharge',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./discharge/discharge').then((m) => m.DischargeComponent),
      },

      // Maternity (history board, OB/GYN referrals, ultrasound, birth plans,
      // prenatal scheduling, postpartum). RECEPTIONIST is included for the
      // prenatal-scheduling tab only; the other tabs are role-gated inside.
      {
        path: 'maternity',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_RECEPTIONIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./maternity/maternity').then((m) => m.MaternityComponent),
      },

      // Prescriptions
      {
        path: 'prescriptions',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_PHARMACIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./prescriptions/prescriptions').then((m) => m.PrescriptionsComponent),
      },

      // Nurse Station
      {
        path: 'nurse-station',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./nurse-station/nurse-station').then((m) => m.NurseStationComponent),
      },

      // Inpatient eMAR — bedside five-rights barcode-scan loop (P1 #8)
      {
        path: 'emar',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_DOCTOR', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./nurse-station/emar/emar.component').then((m) => m.EmarComponent),
      },

      // Patient Tracker Board (MVP 5)
      {
        path: 'patient-tracker',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_RECEPTIONIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./patient-tracker/patient-tracker.component').then(
            (m) => m.PatientTrackerComponent,
          ),
      },

      // Provider In-Basket (MVP 7)
      {
        path: 'in-basket',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_LAB_TECHNICIAN',
            'ROLE_LAB_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./in-basket/in-basket-page').then((m) => m.InBasketPageComponent),
      },

      // Imaging
      {
        path: 'imaging',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_RADIOLOGIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./imaging/imaging').then((m) => m.ImagingComponent),
      },

      // Consultations
      {
        path: 'consultations',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./consultations/consultations').then((m) => m.ConsultationsComponent),
      },

      // Treatment Plans
      {
        path: 'treatment-plans',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./treatment-plans/treatment-plans').then((m) => m.TreatmentPlansComponent),
      },

      // Referrals
      {
        path: 'referrals',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./referrals/referrals').then((m) => m.ReferralsComponent),
      },

      // Audit Logs
      // MVP-5b: SUPER_ADMIN is rewritten to /super-admin/audit-logs;
      // HOSPITAL_ADMIN + ADMIN keep the legacy /audit-logs path.
      {
        path: 'audit-logs',
        canActivate: [superAdminPathRewriteGuard('/super-admin/audit-logs'), RoleGuard],
        data: {
          roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () => import('./audit-logs/audit-logs').then((m) => m.AuditLogsComponent),
      },

      // Consent Management
      {
        path: 'consent-management',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_DOCTOR',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
          ],
        },
        loadComponent: () =>
          import('./consent-management/consent-management.component').then(
            (m) => m.ConsentManagementComponent,
          ),
      },
      {
        path: 'consent-management/shared-records',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
            'ROLE_DOCTOR',
            'ROLE_LAB_DIRECTOR',
            'ROLE_QUALITY_MANAGER',
          ],
        },
        loadComponent: () =>
          import('./consent-management/shared-records-viewer/shared-records-viewer.component').then(
            (m) => m.SharedRecordsViewerComponent,
          ),
      },

      // Reception / Front Desk Cockpit
      {
        path: 'reception',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_RECEPTIONIST', 'ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./reception/reception-cockpit/reception-cockpit.component').then(
            (m) => m.ReceptionCockpitComponent,
          ),
      },

      // EMPI candidate-match panel (roadmap row 25 follow-on).
      // Guard mirrors the backend EmpiProbabilisticController which
      // allows SUPER_ADMIN / HOSPITAL_ADMIN / RECEPTIONIST / NURSE /
      // DOCTOR (per the cell text + the @PreAuthorize on the
      // controller). Mismatching the guard would make the UI
      // unreachable for its intended users — row-32 KPI cards Copilot
      // lesson on PR #341.
      {
        path: 'reception/empi-candidates',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_RECEPTIONIST',
            'ROLE_NURSE',
            'ROLE_DOCTOR',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./reception/empi-candidates-panel/empi-candidates-panel.component').then(
            (m) => m.EmpiCandidatesPanelComponent,
          ),
      },

      // Pharmacy Module
      {
        path: 'medication-catalog',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_PHARMACIST', 'ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./pharmacy/medication-catalog').then((m) => m.MedicationCatalogComponent),
      },
      // Drug-interaction KB curation (P2 #14). Roles mirror the backend
      // READ_ROLES on /drug-interactions exactly; write controls are gated
      // in-component to WRITE_ROLES (PHARMACIST/HOSPITAL_ADMIN/SUPER_ADMIN).
      {
        path: 'pharmacy/drug-interactions',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/drug-interactions').then((m) => m.DrugInteractionsComponent),
      },
      {
        path: 'pharmacy-registry',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_PHARMACIST', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./pharmacy/pharmacy-registry').then((m) => m.PharmacyRegistryComponent),
      },
      {
        path: 'pharmacy/inventory',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_INVENTORY_CLERK',
            'ROLE_STORE_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/inventory-dashboard').then((m) => m.InventoryDashboardComponent),
      },
      {
        path: 'pharmacy/goods-receipt',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_INVENTORY_CLERK',
            'ROLE_STORE_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/goods-receipt').then((m) => m.GoodsReceiptComponent),
      },
      {
        path: 'pharmacy/stock-adjustment',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_INVENTORY_CLERK',
            'ROLE_STORE_MANAGER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/stock-adjustment').then((m) => m.StockAdjustmentComponent),
      },
      {
        path: 'pharmacy/dispensing',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_PHARMACY_VERIFIER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./pharmacy/dispensing').then((m) => m.DispensingComponent),
      },
      {
        path: 'pharmacy/stock-routing',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_PHARMACY_VERIFIER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/stock-routing').then((m) => m.StockRoutingComponent),
      },
      {
        // P-05: deep-link variant — work-queue rows navigate here with the
        // prescription ID pre-filled so users never type or paste a UUID.
        path: 'pharmacy/stock-routing/:prescriptionId',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_PHARMACY_VERIFIER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/stock-routing').then((m) => m.StockRoutingComponent),
      },
      {
        // P-09: pharmacist-led MTM (Medication Therapy Management) review.
        path: 'pharmacy/mtm',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_PHARMACIST', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () => import('./pharmacy/mtm-review').then((m) => m.MtmReviewComponent),
      },
      {
        // T-43/T-44: pharmacy checkout & French printable receipt
        path: 'pharmacy/checkout',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_CASHIER',
            'ROLE_BILLING_SPECIALIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/pharmacy-checkout').then((m) => m.PharmacyCheckoutComponent),
      },
      {
        // T-50: pharmacy insurance claims management (AMU)
        path: 'pharmacy/claims',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_BILLING_SPECIALIST',
            'ROLE_CLAIMS_REVIEWER',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/pharmacy-claims').then((m) => m.PharmacyClaimsComponent),
      },

      // Administration landing for ROLE_ADMIN. ROLE_SUPER_ADMIN passes the
      // role gate but is redirected to /super-admin by SuperAdminRedirectGuard
      // (MVP-5) so the Control Tower stays the single mission-control.
      {
        path: 'admin',
        canActivate: [SuperAdminRedirectGuard, RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./admin/admin').then((m) => m.AdminComponent),
      },

      // Assignment administration. SecurityConfig hard-gates /assignments/**
      // to HOSPITAL_ADMIN + SUPER_ADMIN at the URL layer; the route mirrors it.
      {
        path: 'admin-assignments',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./admin-assignments/admin-assignments').then((m) => m.AdminAssignmentsComponent),
      },

      // Governance console (permission matrix, security policies, user
      // governance, credential health, baselines). SUPER_ADMIN only: most
      // backing endpoints are SUPER_ADMIN-only, and the flat security-policy
      // reads that would admit HOSPITAL_ADMIN are unscoped cross-tenant.
      {
        path: 'admin-governance',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./admin-governance/admin-governance').then((m) => m.AdminGovernanceComponent),
      },

      // Super-Admin Control Tower (SUPER_ADMIN only)
      {
        path: 'super-admin',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./super-admin/super-admin').then((m) => m.SuperAdminComponent),
      },

      // Super-Admin Integration Health Console (MVP-3 — see docs/super-admin-gaps.md)
      {
        path: 'super-admin/integrations',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/integration-health/integration-health').then(
            (m) => m.IntegrationHealthComponent,
          ),
      },

      // Super-Admin Integration Message Log + DLQ + Replay
      // (MVP-c3 — Tier 2 #5 in docs/platform-management-gaps-vs-epic.md)
      {
        path: 'super-admin/integration-messages',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/integration-messages/integration-messages').then(
            (m) => m.IntegrationMessagesComponent,
          ),
      },

      // Super-Admin Cross-Tenant Audit Search (MVP-8 — see docs/super-admin-gaps.md)
      {
        path: 'super-admin/audit-search',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/audit-search/audit-search').then(
            (m) => m.SuperAdminAuditSearchComponent,
          ),
      },

      // Super-Admin Emergency Global Controls (MVP-7 — see docs/super-admin-gaps.md)
      {
        path: 'super-admin/emergency',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/emergency/emergency').then((m) => m.EmergencyComponent),
      },

      // Super-Admin Subscription / Plan Management (MVP-6 — see docs/super-admin-gaps.md)
      {
        path: 'super-admin/subscriptions',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/subscriptions/subscriptions').then((m) => m.SubscriptionsComponent),
      },

      // Per-tenant cost / chargeback panel (roadmap row 44 follow-on).
      // Mirrors the @PreAuthorize(SUPER_ADMIN) on ChargebackReportController.
      {
        path: 'super-admin/cost',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/cost-panel/cost-panel.component').then((m) => m.CostPanelComponent),
      },

      // Super-Admin Data Residency / Region Tagging (MVP-9 — see docs/super-admin-gaps.md)
      {
        path: 'super-admin/data-residency',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/data-residency/data-residency').then(
            (m) => m.DataResidencyComponent,
          ),
      },

      // Super-Admin Data Residency Policy editor (MVP-9c — see docs/super-admin-gaps.md)
      {
        path: 'super-admin/data-residency/policy',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./super-admin/data-residency-policy/data-residency-policy').then(
            (m) => m.DataResidencyPolicyComponent,
          ),
      },

      // MVP-5b — namespaced aliases for the four legacy super-admin
      // surfaces. Loads the same components as the top-level routes;
      // the legacy paths (/feature-flags, /analytics, /audit-logs,
      // /platform) carry a superAdminPathRewriteGuard that bounces a
      // SUPER_ADMIN here while leaving other roles on the legacy URL.
      {
        path: 'super-admin/feature-flags',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./feature-flags/feature-flags').then((m) => m.FeatureFlagsComponent),
      },
      {
        path: 'super-admin/analytics',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./analytics/analytics').then((m) => m.AnalyticsComponent),
      },
      {
        path: 'super-admin/audit-logs',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./audit-logs/audit-logs').then((m) => m.AuditLogsComponent),
      },
      {
        path: 'super-admin/platform',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./platform/platform').then((m) => m.PlatformComponent),
      },

      // Patient education (resource library + assignment/progress).
      // Writes are SUPER_ADMIN/DOCTOR/NURSE on the backend; MIDWIFE is
      // read-only (library browsing); HOSPITAL_ADMIN has no access.
      {
        path: 'patient-education',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./patient-education/patient-education').then((m) => m.PatientEducationComponent),
      },

      // Medication-history timeline + external pharmacy-fill recording.
      // Roles mirror the backend timeline gate exactly (no MIDWIFE, no admins).
      {
        path: 'medication-history',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST', 'ROLE_LAB_SCIENTIST'],
        },
        loadComponent: () =>
          import('./medication-history/medication-history').then(
            (m) => m.MedicationHistoryComponent,
          ),
      },

      // Multi-hospital registrations admin (reception-facing). Writes are
      // RECEPTIONIST/HOSPITAL_ADMIN; other clinical roles read.
      {
        path: 'registrations',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_RECEPTIONIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_DOCTOR',
            'ROLE_NURSE',
            'ROLE_MIDWIFE',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./registrations/registrations').then((m) => m.RegistrationsComponent),
      },

      // Procedure orders (order → consent → schedule → complete/cancel).
      // HOSPITAL_ADMIN is read-only (backend allows lists but not writes).
      {
        path: 'procedure-orders',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./procedure-orders/procedure-orders').then((m) => m.ProcedureOrdersComponent),
      },

      // Refill approval queue (provider-facing — pairs with patient portal refills)
      {
        path: 'refills',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST'] },
        loadComponent: () =>
          import('./refills/refill-approval-list.component').then(
            (m) => m.RefillApprovalListComponent,
          ),
      },

      // CPOE order-set authoring (admin)
      {
        path: 'admin/order-sets',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./admin/order-sets/order-set-list.component').then(
            (m) => m.OrderSetListComponent,
          ),
      },
      {
        path: 'admin/order-sets/:id',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./admin/order-sets/order-set-edit.component').then(
            (m) => m.OrderSetEditComponent,
          ),
      },

      // ADT auto-create intake config (roadmap row 24 admin UI)
      // SUPER_ADMIN only — matches @PreAuthorize on
      // AdtIntakeProviderConfigController.
      {
        path: 'admin/adt-intake-configs',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./admin/adt-intake-config/adt-intake-config.component').then(
            (m) => m.AdtIntakeConfigComponent,
          ),
      },

      // DHIS2 ADX export administration (per-hospital config + mappings + manual trigger)
      {
        path: 'admin/integrations/dhis2',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./admin/integrations/dhis2-admin-page/dhis2-admin-page.component').then(
            (m) => m.Dhis2AdminPageComponent,
          ),
      },

      // Feature Flags Management
      // MVP-5b: legacy /feature-flags is superseded by /super-admin/feature-flags.
      // SUPER_ADMIN gets a transparent rewrite (so bookmarks still work) and
      // the alias below loads the same component.
      {
        path: 'feature-flags',
        canActivate: [superAdminPathRewriteGuard('/super-admin/feature-flags'), RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./feature-flags/feature-flags').then((m) => m.FeatureFlagsComponent),
      },

      // Platform Analytics
      // MVP-5b: legacy /analytics is superseded by /super-admin/analytics
      // for SUPER_ADMIN. Same rewrite + alias pattern as /feature-flags.
      {
        path: 'analytics',
        canActivate: [superAdminPathRewriteGuard('/super-admin/analytics'), RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./analytics/analytics').then((m) => m.AnalyticsComponent),
      },

      // Digital Signatures
      {
        path: 'digital-signatures',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./digital-signatures/digital-signatures').then(
            (m) => m.DigitalSignaturesComponent,
          ),
      },

      // Error pages inside shell
      {
        path: 'error/403',
        loadComponent: () => import('./errors/error-403').then((m) => m.Error403Component),
      },
      {
        path: 'error/404',
        loadComponent: () => import('./errors/error-404').then((m) => m.Error404Component),
      },
    ],
  },

  // Catch-all
  { path: '**', redirectTo: 'login' },
];
