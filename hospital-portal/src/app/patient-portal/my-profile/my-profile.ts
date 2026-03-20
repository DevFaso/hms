import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  PatientProfileDTO,
  PatientProfileUpdateRequest,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-my-profile',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  templateUrl: './my-profile.html',
  styleUrl: './my-profile.scss',
})
export class MyProfileComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  profile = signal<PatientProfileDTO | null>(null);
  loading = signal(true);

  // Edit modal state
  showEditForm = signal(false);
  saving = signal(false);
  editForm = signal<PatientProfileUpdateRequest>({});

  ngOnInit(): void {
    this.portal.getMyProfile().subscribe({
      next: (p) => {
        this.profile.set(p);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  openEditForm(): void {
    const p = this.profile();
    if (!p) return;
    this.editForm.set({
      phoneNumberPrimary: p.phone || '',
      phoneNumberSecondary: '',
      email: p.email || '',
      addressLine1: p.address || '',
      addressLine2: '',
      city: '',
      state: '',
      zipCode: '',
      country: '',
      emergencyContactName: p.emergencyContactName || '',
      emergencyContactPhone: p.emergencyContactPhone || '',
      emergencyContactRelationship: p.emergencyContactRelationship || '',
      preferredPharmacy: '',
    });
    this.showEditForm.set(true);
  }

  closeEditForm(): void {
    this.showEditForm.set(false);
  }

  updateField<K extends keyof PatientProfileUpdateRequest>(
    field: K,
    value: PatientProfileUpdateRequest[K],
  ): void {
    this.editForm.update((f) => ({ ...f, [field]: value }));
  }

  confirmSave(): void {
    if (this.saving()) return;
    this.saving.set(true);

    const dto: PatientProfileUpdateRequest = {};
    const f = this.editForm();
    if (f.phoneNumberPrimary?.trim()) dto.phoneNumberPrimary = f.phoneNumberPrimary.trim();
    if (f.phoneNumberSecondary?.trim()) dto.phoneNumberSecondary = f.phoneNumberSecondary.trim();
    if (f.email?.trim()) dto.email = f.email.trim();
    if (f.addressLine1?.trim()) dto.addressLine1 = f.addressLine1.trim();
    if (f.addressLine2?.trim()) dto.addressLine2 = f.addressLine2.trim();
    if (f.city?.trim()) dto.city = f.city.trim();
    if (f.state?.trim()) dto.state = f.state.trim();
    if (f.zipCode?.trim()) dto.zipCode = f.zipCode.trim();
    if (f.country?.trim()) dto.country = f.country.trim();
    if (f.emergencyContactName?.trim()) dto.emergencyContactName = f.emergencyContactName.trim();
    if (f.emergencyContactPhone?.trim()) dto.emergencyContactPhone = f.emergencyContactPhone.trim();
    if (f.emergencyContactRelationship?.trim())
      dto.emergencyContactRelationship = f.emergencyContactRelationship.trim();
    if (f.preferredPharmacy?.trim()) dto.preferredPharmacy = f.preferredPharmacy.trim();

    this.portal.updateMyProfile(dto).subscribe({
      next: (updated) => {
        this.profile.set(updated);
        this.toast.success('Profile updated successfully.');
        this.saving.set(false);
        this.closeEditForm();
      },
      error: () => {
        this.toast.error('Failed to update profile. Please try again.');
        this.saving.set(false);
      },
    });
  }

  getInitials(): string {
    const p = this.profile();
    if (!p) return '?';
    return ((p.firstName?.charAt(0) || '') + (p.lastName?.charAt(0) || '')).toUpperCase();
  }
}
