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
      // Clinical
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'Document Nursing Notes',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      // Consent & record sharing
      'View Record Sharing',
      'Manage Patient Consents',
      // Admin
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
      'Register Patients',
      // Clinical — gates nav items
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'View Prescriptions',
      'Document Nursing Notes',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      // Consent & record sharing
      'View Record Sharing',
      'Manage Patient Consents',
      // Lab / Lab orders
      'Order Lab Tests',
      'View Lab',
      // Scheduling
      'View Staff Schedules',
      // Notifications
      'View Notifications',
    ],
    // ROLE_PHYSICIAN and ROLE_SURGEON inherit the same clinical permissions as ROLE_DOCTOR
    ROLE_PHYSICIAN: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'View Prescriptions',
      'Document Nursing Notes',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      'View Record Sharing',
      'Manage Patient Consents',
      'Order Lab Tests',
      'View Lab',
      'View Staff Schedules',
      'View Notifications',
    ],
    ROLE_SURGEON: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      'Create Encounters',
      'Admit Patients',
      'Create Prescriptions',
      'View Prescriptions',
      'Document Nursing Notes',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      'View Record Sharing',
      'Manage Patient Consents',
      'Order Lab Tests',
      'View Lab',
      'View Staff Schedules',
      'View Notifications',
    ],
    ROLE_NURSE: [
      'View Dashboard',
      'View Appointments',
      'Schedule Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      // Clinical
      'Create Encounters',
      'Document Nursing Notes',
      'Create Prescriptions',
      'View Prescriptions',
      'Request Imaging Studies',
      'Request Consultations',
      'Create Treatment Plans',
      'Create Referrals',
      'Admit Patients',
      // Record sharing (read-only — cannot manage consents)
      'View Record Sharing',
      // Scheduling
      'View Staff Schedules',
      'Check-in Patients',
      // Vitals / meds
      'Update Vital Signs',
      'Administer Medications',
      'View Lab',
      // Notifications
      'View Notifications',
    ],
    ROLE_MIDWIFE: [
      'View Dashboard',
      'View Appointments',
      'View Patient Records',
      'Update Patient Records',
      'Register Patients',
      // Clinical
      'Create Encounters',
      'Admit Patients',
      'Document Nursing Notes',
      'Create Prescriptions',
      'Create Referrals',
      'Create Treatment Plans',
      // Record sharing (read-only — cannot manage consents)
      'View Record Sharing',
      // Maternity-specific
      'Perform Prenatal Assessments',
      'Create Birth Plans',
      'Perform Postpartum Care',
      // Notifications
      'View Notifications',
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
      // Billing (co-pay collection)
      'View Billing',
      // Notifications
      'View Notifications',
    ],
    ROLE_LAB_SCIENTIST: [
      'View Dashboard',
      'View Lab',
      'Process Lab Tests',
      'View Patient Records',
      'View Notifications',
    ],
    ROLE_PHARMACIST: [
      'View Dashboard',
      'View Prescriptions',
      'Create Prescriptions',
      'Dispense Medications',
      'View Patient Records',
      'View Notifications',
    ],
    ROLE_RADIOLOGIST: [
      'View Dashboard',
      'View Patient Records',
      'Request Imaging Studies',
      'View Notifications',
    ],
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
