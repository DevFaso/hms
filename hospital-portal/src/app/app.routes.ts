import { Routes } from '@angular/router';
import { AuthGuard } from './auth/auth.guard';
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

  // Authenticated shell
  {
    path: '',
    canActivate: [AuthGuard],
    loadComponent: () => import('./shell/shell').then((m) => m.ShellComponent),
    children: [
      {
        path: 'dashboard',
        loadComponent: () => import('./dashboard/dashboard').then((m) => m.DashboardComponent),
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
        ],
      },

      // Staff
      {
        path: 'staff',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
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
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
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
        data: { roles: ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'ROLE_BILLING'] },
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
            'ROLE_LAB_TECHNICIAN',
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

      // Settings → redirect to profile (edit tab)
      { path: 'settings', redirectTo: 'profile', pathMatch: 'full' },

      // Notifications
      {
        path: 'notifications',
        loadComponent: () =>
          import('./notifications/notification-list').then((m) => m.NotificationListComponent),
      },

      // Chat / Messages
      {
        path: 'chat',
        loadComponent: () => import('./chat/chat').then((m) => m.ChatComponent),
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
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./audit-logs/audit-logs').then((m) => m.AuditLogsComponent),
      },

      // Administration
      {
        path: 'admin',
        canActivate: [RoleGuard],
        data: { roles: ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] },
        loadComponent: () => import('./admin/admin').then((m) => m.AdminComponent),
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
