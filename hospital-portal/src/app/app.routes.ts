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
    path: 'mfa-challenge',
    loadComponent: () => import('./mfa/mfa-challenge').then((m) => m.MfaChallengeComponent),
  },
  {
    path: 'mfa-enroll',
    canActivate: [AuthGuard],
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

      // Settings → redirect to profile (edit tab)
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
      {
        path: 'audit-logs',
        canActivate: [RoleGuard],
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

      // Pharmacy Module
      {
        path: 'medication-catalog',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_DOCTOR',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
        },
        loadComponent: () =>
          import('./pharmacy/medication-catalog').then((m) => m.MedicationCatalogComponent),
      },
      {
        path: 'pharmacy-registry',
        canActivate: [RoleGuard],
        data: {
          roles: [
            'ROLE_PHARMACIST',
            'ROLE_HOSPITAL_ADMIN',
            'ROLE_SUPER_ADMIN',
          ],
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
