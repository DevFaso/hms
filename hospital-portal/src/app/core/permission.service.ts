import { Injectable, inject } from '@angular/core';

import { AuthService } from '../auth/auth.service';

@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly auth = inject(AuthService);

  private readonly ROLE_PERMISSIONS: Record<string, string[]> = {
    ROLE_SUPER_ADMIN: ['*'],
    ROLE_HOSPITAL_ADMIN: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'Create Appointments',
      'Cancel Appointments',
      'Reschedule Appointments',
      'View Patient Records',
      'Access All Patient Records',
      'Update Patient Records',
      'Register Patients',
      'View Staff',
      'Manage Staff',
      'View Staff Schedules',
      'Manage Staff Schedules',
      'View Departments',
      'Manage Departments',
      'View Roles',
      'Manage Roles',
      'View Audit Logs',
      'View Billing',
      'Manage Billing',
      'View Lab',
      'Order Lab Tests',
      'View Notifications',
      'Manage Hospitals',
    ],
    ROLE_DOCTOR: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Order Lab Tests',
      'View Lab',
      'View Staff Schedules',
    ],
    ROLE_NURSE: [
      'View Dashboard',
      'View Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Update Vital Signs',
      'Administer Medications',
      'View Lab',
    ],
    ROLE_MIDWIFE: [
      'View Dashboard',
      'View Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Perform Prenatal Assessments',
      'Create Birth Plans',
      'Perform Postpartum Care',
    ],
    ROLE_RECEPTIONIST: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'Create Appointments',
      'Cancel Appointments',
      'Reschedule Appointments',
      'Check-in Patients',
      'Register Patients',
      'View Patient Records',
      'Update Patient Contact Info',
    ],
    ROLE_LAB_SCIENTIST: ['View Dashboard', 'View Lab', 'Process Lab Tests', 'View Patient Records'],
    ROLE_PATIENT: ['View Dashboard', 'View Appointments', 'View Patient Records'],
  };

  hasPermission(permission: string): boolean {
    const roles = this.auth.getRoles();
    for (const role of roles) {
      const perms = this.ROLE_PERMISSIONS[role];
      if (perms?.includes('*') || perms?.includes(permission)) {
        return true;
      }
    }
    return false;
  }

  hasAnyPermission(...permissions: string[]): boolean {
    return permissions.some((p) => this.hasPermission(p));
  }

  hasAllPermissions(...permissions: string[]): boolean {
    return permissions.every((p) => this.hasPermission(p));
  }
}
