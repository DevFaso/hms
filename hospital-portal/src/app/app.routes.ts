import { Routes } from '@angular/router';
import { AuthGuard } from './auth/auth.guard';
import { AccountSetupGuard } from './auth/account-setup.guard';
import { LoginRedirectGuard } from './auth/login-redirect.guard';
import { RoleGuard } from './auth/role.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  // Public
  {
    path: 'login',
    canActivate: [LoginRedirectGuard],
    loadComponent: () => import('./login/login').then((m) => m.Login),
  },
  {
    path: 'reset-password',
    loadComponent: () =>
      import('./reset-password/reset-password').then((m) => m.ResetPasswordComponent),
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
        loadComponent: () => import('./dashboard/dashboard').then((m) => m.DashboardComponent),
      },

      // â”€â”€â”€ Patient Portal (ROLE_PATIENT only) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
      {
        path: 'my-profile',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-profile/my-profile').then((m) => m.MyProfileComponent),
      },
      {
        path: 'my-appointments',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-appointments/my-appointments').then(
            (m) => m.MyAppointmentsComponent,
          ),
      },
      {
        path: 'my-medications',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-medications/my-medications').then(
            (m) => m.MyMedicationsComponent,
          ),
      },
      {
        path: 'my-refills',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-refills/my-refills').then((m) => m.MyRefillsComponent),
      },
      {
        path: 'my-lab-results',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-lab-results/my-lab-results').then(
            (m) => m.MyLabResultsComponent,
          ),
      },
      {
        path: 'my-vitals',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-vitals/my-vitals').then((m) => m.MyVitalsComponent),
      },
      {
        path: 'my-billing',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-billing/my-billing').then((m) => m.MyBillingComponent),
      },
      {
        path: 'my-visits',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-visits/my-visits').then((m) => m.MyVisitsComponent),
      },
      {
        path: 'my-care-team',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-care-team/my-care-team').then((m) => m.MyCareTeamComponent),
      },
      {
        path: 'my-records',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-records/my-records').then((m) => m.MyRecordsComponent),
      },
      {
        path: 'my-sharing',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-sharing/my-sharing').then((m) => m.MySharingComponent),
      },
      {
        path: 'my-family-access',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-family-access/my-family-access').then(
            (m) => m.MyFamilyAccessComponent,
          ),
      },
      {
        path: 'my-summaries',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-summaries/my-summaries').then((m) => m.MySummariesComponent),
      },
      {
        path: 'my-notifications',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-notifications/my-notifications').then(
            (m) => m.MyNotificationsComponent,
          ),
      },
      {
        path: 'my-vital-trends',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-vital-trends/my-vital-trends').then(
            (m) => m.MyVitalTrendsComponent,
          ),
      },
      {
        path: 'my-upcoming-vaccines',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-upcoming-vaccines/my-upcoming-vaccines').then(
            (m) => m.MyUpcomingVaccinesComponent,
          ),
      },
      {
        path: 'my-lab-orders',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-lab-orders/my-lab-orders').then(
            (m) => m.MyLabOrdersComponent,
          ),
      },
      {
        path: 'my-imaging-orders',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-imaging-orders/my-imaging-orders').then(
            (m) => m.MyImagingOrdersComponent,
          ),
      },
      {
        path: 'my-pharmacy-fills',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-pharmacy-fills/my-pharmacy-fills').then(
            (m) => m.MyPharmacyFillsComponent,
          ),
      },
      {
        path: 'my-procedures',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-procedures/my-procedures').then(
            (m) => m.MyProceduresComponent,
          ),
      },
      {
        path: 'my-admissions',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-admissions/my-admissions').then(
            (m) => m.MyAdmissionsComponent,
          ),
      },
      {
        path: 'my-education-progress',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-education-progress/my-education-progress').then(
            (m) => m.MyEducationProgressComponent,
          ),
      },
      {
        path: 'my-education-browse',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-education-browse/my-education-browse').then(
            (m) => m.MyEducationBrowseComponent,
          ),
      },
      {
        path: 'my-records-download',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-records-download/my-records-download').then(
            (m) => m.MyRecordsDownloadComponent,
          ),
      },
      {
        path: 'my-lab-trends',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-lab-trends/my-lab-trends').then(
            (m) => m.MyLabTrendsComponent,
          ),
      },
      {
        path: 'my-checkin',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-checkin/my-checkin').then((m) => m.MyCheckinComponent),
      },
      {
        path: 'my-treatment-plans',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-treatment-plans/my-treatment-plans').then(
            (m) => m.MyTreatmentPlansComponent,
          ),
      },
      {
        path: 'my-referrals',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-referrals/my-referrals').then((m) => m.MyReferralsComponent),
      },
      {
        path: 'my-consultations',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-consultations/my-consultations').then(
            (m) => m.MyConsultationsComponent,
          ),
      },

      // My Questionnaires (ROLE_PATIENT)
      {
        path: 'my-questionnaires',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-questionnaires/my-questionnaires').then(
            (m) => m.MyQuestionnairesComponent,
          ),
      },

      // Health Maintenance Reminders (ROLE_PATIENT)
      {
        path: 'my-reminders',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-reminders/my-reminders').then((m) => m.MyRemindersComponent),
      },

      // Patient-Reported Outcomes (ROLE_PATIENT)
      {
        path: 'my-outcomes',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_PATIENT'] },
        loadComponent: () =>
          import('./patient-portal/my-outcomes/my-outcomes').then((m) => m.MyOutcomesComponent),
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
          roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'ROLE_RECEPTIONIST'],
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
          ],
        },
        loadComponent: () => import('./scheduling/scheduling').then((m) => m.SchedulingComponent),
      },

      // Departments
      {
        path: 'departments',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'ROLE_RECEPTIONIST'],
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
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () => import('./lab/lab').then((m) => m.LabComponent),
      },

      // Profile (all authenticated users)
      {
        path: 'profile',
        loadComponent: () => import('./profile/profile').then((m) => m.ProfileComponent),
      },

      // Settings â†’ redirect to profile (edit tab)
      { path: 'settings', redirectTo: 'profile', pathMatch: 'full' },

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

      // Organizations (Admin)
      {
        path: 'organizations',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./organizations/organization-list').then((m) => m.OrganizationListComponent),
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
      {
        path: 'platform',
        canActivate: [RoleGuard],
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
      {
        path: 'audit-logs',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./audit-logs/audit-logs').then((m) => m.AuditLogsComponent),
      },

      // Reception / Front Desk Cockpit
      {
        path: 'reception',
        canActivate: [RoleGuard],
        data: {
          roles: ['ROLE_RECEPTIONIST', 'ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'],
        },
        loadComponent: () =>
          import('./reception/reception-cockpit').then((m) => m.ReceptionCockpitComponent),
      },

      // Administration
      {
        path: 'admin',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./admin/admin').then((m) => m.AdminComponent),
      },

      // Feature Flags Management
      {
        path: 'feature-flags',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_SUPER_ADMIN'] },
        loadComponent: () =>
          import('./feature-flags/feature-flags').then((m) => m.FeatureFlagsComponent),
      },

      // Platform Analytics
      {
        path: 'analytics',
        canActivate: [RoleGuard],
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
