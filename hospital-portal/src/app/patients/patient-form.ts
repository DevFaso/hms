import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PatientService, PatientCreateRequest } from '../services/patient.service';
import { UserService } from '../services/user.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { RoleContextService } from '../core/role-context.service';
import { switchMap } from 'rxjs/operators';

@Component({
  selector: 'app-patient-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './patient-form.html',
  styleUrl: './patient-form.scss',
})
export class PatientFormComponent implements OnInit {
  private readonly patientService = inject(PatientService);
  private readonly userService = inject(UserService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly hospitalService = inject(HospitalService);
  private readonly roleContext = inject(RoleContextService);

  saving = false;
  hospitals: HospitalResponse[] = [];
  /** true when the logged-in user already has a hospital in their JWT / context */
  hasContextHospital = false;

  form: PatientCreateRequest = {
    userId: '',
    hospitalId: this.auth.getHospitalId() ?? '',
    firstName: '',
    lastName: '',
    middleName: '',
    email: '',
    phoneNumberPrimary: '',
    phoneNumberSecondary: '',
    dateOfBirth: '',
    gender: '',
    address: '',
    city: '',
    state: '',
    zipCode: '',
    country: '',
    bloodType: '',
    emergencyContactName: '',
    emergencyContactPhone: '',
    emergencyContactRelationship: '',
    allergies: '',
    medicalHistorySummary: '',
  };

  ngOnInit(): void {
    this.hasContextHospital = !!this.auth.getHospitalId();
    // ── TENANT ISOLATION: only SUPER_ADMIN may choose from all hospitals ──
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe({
        next: (list) => (this.hospitals = list),
        error: () => this.toast.error('Failed to load hospitals'),
      });
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals = [h];
          this.form.hospitalId = h.id;
        },
        error: () => this.toast.error('Failed to load hospital'),
      });
    }
  }

  get lockedHospitalName(): string {
    return this.hospitals.length === 1 ? this.hospitals[0].name : 'No hospital assigned';
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  onSubmit(): void {
    if (
      !this.form.firstName ||
      !this.form.lastName ||
      !this.form.email ||
      !this.form.phoneNumberPrimary ||
      !this.form.gender ||
      !this.form.dateOfBirth ||
      !this.form.country ||
      !this.form.city
    ) {
      this.toast.error('Please fill in all required fields');
      return;
    }
    if (!this.form.hospitalId) {
      this.toast.error('Please select a hospital');
      return;
    }
    this.saving = true;

    // Step 1: Create a User account with ROLE_PATIENT via admin-register
    // Step 2: Use the returned userId to create the Patient entity
    const username = this.generateUsername(this.form.firstName, this.form.lastName);
    this.userService
      .adminRegister({
        username,
        email: this.form.email,
        firstName: this.form.firstName,
        lastName: this.form.lastName,
        phoneNumber: this.form.phoneNumberPrimary,
        roleNames: ['PATIENT'],
        hospitalId: this.form.hospitalId,
        forcePasswordChange: true,
      })
      .pipe(
        switchMap((user) => {
          this.form.userId = user.id;
          return this.patientService.create(this.form);
        }),
      )
      .subscribe({
        next: (patient) => {
          this.toast.success(
            'Patient registered successfully. A verification email has been sent.',
          );
          this.router.navigate(['/patients', patient.id]);
        },
        error: (err) => {
          this.toast.error(err?.error?.message ?? 'Failed to register patient');
          this.saving = false;
        },
      });
  }

  /** Generate a unique-ish username from first + last name */
  private generateUsername(first: string, last: string): string {
    const base = (first.charAt(0) + last).toLowerCase().replace(/[^a-z0-9]/g, '');
    const suffix = Math.floor(1000 + Math.random() * 9000);
    return `pat_${base}${suffix}`;
  }
}
