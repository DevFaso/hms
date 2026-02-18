import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PatientService, PatientCreateRequest } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-patient-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './patient-form.html',
  styleUrl: './patient-form.scss',
})
export class PatientFormComponent {
  private readonly patientService = inject(PatientService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  saving = false;
  form: PatientCreateRequest = {
    hospitalId: this.auth.getHospitalId() ?? '',
    firstName: '',
    lastName: '',
    email: '',
    phoneNumber: '',
    dateOfBirth: '',
    gender: '',
    address: '',
    bloodGroup: '',
  };

  onSubmit(): void {
    if (!this.form.firstName || !this.form.lastName || !this.form.email) return;
    this.saving = true;
    this.patientService.create(this.form).subscribe({
      next: (patient) => {
        this.toast.success('Patient registered successfully');
        this.router.navigate(['/patients', patient.id]);
      },
      error: () => {
        this.toast.error('Failed to register patient');
        this.saving = false;
      },
    });
  }
}
