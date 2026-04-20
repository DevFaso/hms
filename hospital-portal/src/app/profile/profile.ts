import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { MfaService, MfaEnrollmentResponse } from '../auth/mfa.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule } from '@ngx-translate/core';
import {
  ProfileService,
  UserProfile,
  CredentialHealth,
  Assignment,
  AuditEvent,
  ProfileUpdateRequest,
  RecoveryContact,
} from '../services/profile.service';

type ProfileTab = 'overview' | 'edit' | 'security' | 'activity';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './profile.html',
  styleUrl: './profile.scss',
})
export class ProfileComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly mfaService = inject(MfaService);
  private readonly profileService = inject(ProfileService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  /* ── State ── */
  activeTab = signal<ProfileTab>('overview');
  loading = signal(true);
  saving = signal(false);
  uploadingAvatar = signal(false);

  user = signal<UserProfile | null>(null);
  credentials = signal<CredentialHealth | null>(null);
  assignments = signal<Assignment[]>([]);
  activityLog = signal<AuditEvent[]>([]);
  activityLoading = signal(false);

  /* ── Edit form model ── */
  editForm = signal<ProfileUpdateRequest>({});
  formDirty = signal(false);

  /* ── Computed ── */
  userInitials = computed(() => {
    const u = this.user();
    if (!u) return '?';
    return `${u.firstName?.charAt(0) ?? ''}${u.lastName?.charAt(0) ?? ''}`.toUpperCase();
  });

  fullName = computed(() => {
    const u = this.user();
    return u ? `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() : '';
  });

  primaryRole = computed(() => {
    const u = this.user();
    if (!u?.roles?.length) return '';
    const role = u.roleName ?? u.roles[0]?.name ?? u.roles[0]?.code ?? '';
    return this.auth.formatRole(role.startsWith('ROLE_') ? role : `ROLE_${role}`);
  });

  allRoles = computed(() => {
    const u = this.user();
    if (!u?.roles) return [];
    return u.roles.map((r) => ({
      ...r,
      displayName: this.auth.formatRole(r.code ?? r.name ?? ''),
    }));
  });

  /**
   * True when the JWT carries any administrative / clinical staff role.
   * A user logged in through the staff portal is ALWAYS treated as staff here,
   * even if a patient record happens to exist for them in the DB.
   */
  isStaffOrAdmin = computed(() => {
    const staffRoles = [
      'ROLE_SUPER_ADMIN',
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_ADMIN',
      'ROLE_DOCTOR',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_RECEPTIONIST',
      'ROLE_LAB_SCIENTIST',
      'ROLE_STAFF',
    ];
    return this.auth.hasAnyRole(staffRoles);
  });

  isSuperAdmin = computed(() => this.auth.hasAnyRole(['ROLE_SUPER_ADMIN']));

  /**
   * The profile-type label to display — derived from the JWT session roles,
   * never from the raw `profileType` DB field returned by the API.
   * A Super Admin who also happens to have a patient record should see
   * "Super Admin" here, not "PATIENT".
   */
  sessionProfileType = computed((): string => {
    const roles = this.auth.getRoles();
    const priority: [string, string][] = [
      ['ROLE_SUPER_ADMIN', 'Super Admin'],
      ['ROLE_HOSPITAL_ADMIN', 'Hospital Admin'],
      ['ROLE_ADMIN', 'Administrator'],
      ['ROLE_DOCTOR', 'Doctor'],
      ['ROLE_NURSE', 'Nurse'],
      ['ROLE_MIDWIFE', 'Midwife'],
      ['ROLE_RECEPTIONIST', 'Receptionist'],
      ['ROLE_LAB_SCIENTIST', 'Lab Scientist'],
      ['ROLE_STAFF', 'Staff Member'],
      ['ROLE_PATIENT', 'Patient'],
    ];
    for (const [role, label] of priority) {
      if (roles.includes(role)) return label;
    }
    return 'User';
  });

  /** Show license number only for clinical staff roles in this session. */
  showLicenseNumber = computed(() => {
    const clinicalRoles = ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST'];
    return this.auth.hasAnyRole(clinicalRoles) && !!this.user()?.licenseNumber;
  });

  memberSince = computed(() => {
    const u = this.user();
    if (!u?.createdAt) return '';
    return new Date(u.createdAt).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  });

  lastLogin = computed(() => {
    const u = this.user();
    if (!u?.lastLoginAt) return 'Never';
    return new Date(u.lastLoginAt).toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  });

  securityScore = computed(() => {
    const c = this.credentials();
    if (!c) return 0;
    let score = 20; // base: account active
    if (!c.forcePasswordChange) score += 20;
    if (c.hasPrimaryMfa) score += 30;
    if (c.hasPrimaryRecoveryContact) score += 20;
    if (c.verifiedMfaCount > 0) score += 10;
    return Math.min(score, 100);
  });

  securityScoreColor = computed(() => {
    const s = this.securityScore();
    if (s >= 80) return '#059669';
    if (s >= 50) return '#d97706';
    return '#dc2626';
  });

  securityScoreLabel = computed(() => {
    const s = this.securityScore();
    if (s >= 80) return 'Excellent';
    if (s >= 50) return 'Good';
    return 'Needs Attention';
  });

  accountAge = computed(() => {
    const u = this.user();
    if (!u?.createdAt) return '';
    const created = new Date(u.createdAt);
    const now = new Date();
    const diffMs = now.getTime() - created.getTime();
    const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    if (days < 1) return 'Today';
    if (days < 30) return `${days} day${days > 1 ? 's' : ''}`;
    const months = Math.floor(days / 30);
    if (months < 12) return `${months} month${months > 1 ? 's' : ''}`;
    const years = Math.floor(months / 12);
    return `${years} year${years > 1 ? 's' : ''}`;
  });

  /* ── Inline MFA Enrollment ── */
  mfaStep = signal<'idle' | 'qr' | 'verify' | 'backup'>('idle');
  mfaLoading = signal(false);
  mfaError = signal('');
  mfaEnrollment = signal<MfaEnrollmentResponse | null>(null);
  mfaTotpCode = '';

  startMfaEnrollment(): void {
    this.mfaLoading.set(true);
    this.mfaError.set('');
    this.mfaService.enroll().subscribe({
      next: (res) => {
        this.mfaEnrollment.set(res);
        this.mfaStep.set('qr');
        this.mfaLoading.set(false);
      },
      error: (err) => {
        this.mfaError.set(err?.error?.message ?? 'Failed to start MFA enrollment.');
        this.mfaLoading.set(false);
      },
    });
  }

  verifyMfaCode(): void {
    if (!this.mfaTotpCode || this.mfaTotpCode.length < 6) {
      this.mfaError.set('Please enter a valid 6-digit code.');
      return;
    }
    this.mfaLoading.set(true);
    this.mfaError.set('');
    this.mfaService.verifyEnrollment(this.mfaTotpCode).subscribe({
      next: () => {
        this.mfaStep.set('backup');
        this.mfaLoading.set(false);
        this.toast.success('MFA enrolled successfully!');
        // Refresh credential health to update the MFA status display
        this.profileService.getCredentialHealth().subscribe({
          next: (creds) => this.credentials.set(creds),
        });
      },
      error: (err) => {
        this.mfaError.set(err?.error?.message ?? 'Invalid code. Please try again.');
        this.mfaLoading.set(false);
      },
    });
  }

  finishMfaEnrollment(): void {
    this.mfaStep.set('idle');
    this.mfaEnrollment.set(null);
    this.mfaTotpCode = '';
    this.mfaError.set('');
  }

  copyMfaBackupCodes(): void {
    const codes = this.mfaEnrollment()?.backupCodes ?? [];
    navigator.clipboard.writeText(codes.join('\n')).catch(() => {
      /* ignore */
    });
    this.toast.success('Backup codes copied to clipboard.');
  }

  /* ── Recovery Contacts ── */
  showAddRecovery = signal(false);
  newRecoveryType = signal<'EMAIL' | 'PHONE'>('EMAIL');
  newRecoveryValue = signal('');
  newRecoveryPrimary = signal(false);
  recoverySaving = signal(false);
  recoveryError = signal('');

  openAddRecovery(): void {
    this.showAddRecovery.set(true);
    this.newRecoveryType.set('EMAIL');
    this.newRecoveryValue.set('');
    this.newRecoveryPrimary.set(false);
    this.recoveryError.set('');
  }

  cancelAddRecovery(): void {
    this.showAddRecovery.set(false);
    this.recoveryError.set('');
  }

  saveRecoveryContact(): void {
    const value = this.newRecoveryValue().trim();
    if (!value) {
      this.recoveryError.set('Please enter a contact value.');
      return;
    }
    this.recoverySaving.set(true);
    this.recoveryError.set('');

    const existing = (this.credentials()?.recoveryContacts ?? []).map((rc) => ({
      contactType: rc.contactType,
      contactValue: rc.contactValue,
      verified: rc.verified,
      primaryContact: this.newRecoveryPrimary() ? false : rc.primaryContact,
    }));

    const payload = [
      ...existing,
      {
        contactType: this.newRecoveryType(),
        contactValue: value,
        verified: true,
        primaryContact: this.newRecoveryPrimary(),
      },
    ];

    this.profileService.updateRecoveryContacts(payload).subscribe({
      next: () => {
        this.recoverySaving.set(false);
        this.showAddRecovery.set(false);
        this.toast.success('Recovery contact added.');
        this.profileService.getCredentialHealth().subscribe({
          next: (creds) => this.credentials.set(creds),
        });
      },
      error: (err) => {
        this.recoverySaving.set(false);
        this.recoveryError.set(err?.error?.message ?? 'Failed to save recovery contact.');
      },
    });
  }

  removeRecoveryContact(contact: RecoveryContact): void {
    this.recoverySaving.set(true);
    const remaining = (this.credentials()?.recoveryContacts ?? [])
      .filter((rc) => rc.id !== contact.id)
      .map((rc) => ({
        contactType: rc.contactType,
        contactValue: rc.contactValue,
        verified: rc.verified,
        primaryContact: rc.primaryContact,
      }));

    this.profileService.updateRecoveryContacts(remaining).subscribe({
      next: () => {
        this.recoverySaving.set(false);
        this.toast.success('Recovery contact removed.');
        this.profileService.getCredentialHealth().subscribe({
          next: (creds) => this.credentials.set(creds),
        });
      },
      error: (err) => {
        this.recoverySaving.set(false);
        this.toast.error(err?.error?.message ?? 'Failed to remove contact.');
      },
    });
  }

  /* ── Lifecycle ── */
  ngOnInit(): void {
    this.loadProfile();
  }

  /* ── Data Loading ── */
  private loadProfile(): void {
    this.loading.set(true);

    // Prefer the user ID from the JWT token (always authoritative)
    const jwtUserId = this.auth.getUserId();
    const storedProfile = this.auth.getUserProfile();
    const userId = jwtUserId ?? storedProfile?.id;

    if (!userId) {
      this.toast.error('Unable to load profile. Please log in again.');
      this.router.navigateByUrl('/login');
      return;
    }

    let pending = 3;
    const done = () => {
      if (--pending <= 0) this.loading.set(false);
    };

    // 1. Full profile from /users/:id
    this.profileService.getUserProfile(userId).subscribe({
      next: (profile) => {
        this.user.set(profile);
        this.resetEditForm(profile);

        // Sync stored profile ID if stale
        if (storedProfile && storedProfile.id !== profile.id) {
          storedProfile.id = profile.id;
          this.auth.setUserProfile(storedProfile);
        }
        done();
      },
      error: () => {
        // Fallback to stored profile
        if (storedProfile) {
          this.user.set(this.storedToUserProfile(storedProfile));
          this.resetEditForm(this.user()!);
        }
        done();
      },
    });

    // 2. Credential health
    this.profileService.getCredentialHealth().subscribe({
      next: (creds) => {
        this.credentials.set(creds);
        done();
      },
      error: () => done(),
    });

    // 3. Role assignments
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        this.assignments.set(assignments);
        done();
      },
      error: () => done(),
    });
  }

  loadActivity(): void {
    const u = this.user();
    if (!u) return;
    this.activityLoading.set(true);
    this.profileService.getActivityLog(u.id).subscribe({
      next: (res) => {
        this.activityLog.set(res.content ?? []);
        this.activityLoading.set(false);
      },
      error: () => {
        this.activityLog.set([]);
        this.activityLoading.set(false);
      },
    });
  }

  /* ── Tab Navigation ── */
  switchTab(tab: ProfileTab): void {
    this.activeTab.set(tab);
    if (tab === 'activity' && this.activityLog().length === 0) {
      this.loadActivity();
    }
  }

  /* ── Edit Profile ── */
  private resetEditForm(profile: UserProfile): void {
    this.editForm.set({
      firstName: profile.firstName ?? '',
      lastName: profile.lastName ?? '',
      email: profile.email ?? '',
      phoneNumber: profile.phoneNumber ?? '',
      username: profile.username ?? '',
    });
    this.formDirty.set(false);
  }

  markDirty(): void {
    this.formDirty.set(true);
  }

  updateField(field: keyof ProfileUpdateRequest, value: string): void {
    this.editForm.update((f) => ({ ...f, [field]: value }));
    this.formDirty.set(true);
  }

  cancelEdit(): void {
    const u = this.user();
    if (u) this.resetEditForm(u);
    this.activeTab.set('overview');
  }

  saveProfile(): void {
    const u = this.user();
    if (!u || this.saving()) return;

    this.saving.set(true);
    const data = this.editForm();

    this.profileService.updateProfile(u.id, data).subscribe({
      next: (updated) => {
        this.user.set(updated);
        this.resetEditForm(updated);
        this.saving.set(false);
        this.toast.success('Profile updated successfully!');

        // Update stored login profile
        const stored = this.auth.getUserProfile();
        if (stored) {
          stored.firstName = updated.firstName;
          stored.lastName = updated.lastName;
          stored.email = updated.email;
          stored.phoneNumber = updated.phoneNumber;
          stored.profileImageUrl = updated.profileImageUrl;
          this.auth.setUserProfile(stored);
        }
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(err?.error?.message ?? 'Failed to update profile.');
      },
    });
  }

  /* ── Avatar ── */
  onAvatarFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input?.files?.[0];
    if (!file) return;

    // Validate file
    const maxSize = 5 * 1024 * 1024; // 5MB
    if (file.size > maxSize) {
      this.toast.error('Image must be less than 5MB.');
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.toast.error('Please select an image file.');
      return;
    }

    this.uploadingAvatar.set(true);
    this.profileService.uploadAvatar(file).subscribe({
      next: (res) => {
        this.uploadingAvatar.set(false);
        const u = this.user();
        if (u) {
          this.user.set({ ...u, profileImageUrl: res.imageUrl });
        }
        // Update stored profile
        const stored = this.auth.getUserProfile();
        if (stored) {
          stored.profileImageUrl = res.imageUrl;
          this.auth.setUserProfile(stored);
        }
        this.toast.success('Profile photo updated!');
      },
      error: (err) => {
        this.uploadingAvatar.set(false);
        this.toast.error(err?.error?.message ?? 'Failed to upload photo.');
      },
    });
    // Reset input so same file can be selected again
    input.value = '';
  }

  removeAvatar(): void {
    if (this.uploadingAvatar()) return;
    this.uploadingAvatar.set(true);
    this.profileService.deleteAvatar().subscribe({
      next: () => {
        this.uploadingAvatar.set(false);
        const u = this.user();
        if (u) {
          this.user.set({ ...u, profileImageUrl: undefined });
        }
        const stored = this.auth.getUserProfile();
        if (stored) {
          stored.profileImageUrl = undefined;
          this.auth.setUserProfile(stored);
        }
        this.toast.success('Profile photo removed.');
      },
      error: () => {
        this.uploadingAvatar.set(false);
        this.toast.error('Failed to remove photo.');
      },
    });
  }

  /* ── Security Actions ── */
  requestPasswordReset(): void {
    const u = this.user();
    if (!u?.email) {
      this.toast.error('No email address on file.');
      return;
    }
    this.profileService.requestPasswordReset(u.email).subscribe({
      next: () => this.toast.success('Password reset link sent to your email.'),
      error: () => this.toast.error('Failed to request password reset.'),
    });
  }

  /* ── Helpers ── */
  private storedToUserProfile(stored: LoginUserProfile): UserProfile {
    return {
      id: stored.id,
      username: stored.username,
      email: stored.email,
      firstName: stored.firstName ?? '',
      lastName: stored.lastName ?? '',
      phoneNumber: stored.phoneNumber,
      profileImageUrl: stored.profileImageUrl,
      active: stored.active,
      roles: (stored.roles ?? []).map((r) => ({ id: '', code: r, name: r })),
      profileType: stored.profileType,
      licenseNumber: stored.licenseNumber,
      roleName: stored.roleName,
      staffId: stored.staffId,
    };
  }

  getActivityIcon(eventType: string): string {
    const map: Record<string, string> = {
      LOGIN: 'login',
      LOGOUT: 'logout',
      PASSWORD_RESET_REQUEST: 'lock_reset',
      PASSWORD_RESET_COMPLETE: 'lock_open',
      USER_CREATE: 'person_add',
      USER_UPDATE: 'edit',
      USER_DELETE: 'person_remove',
      PATIENT_CREATE: 'personal_injury',
      PATIENT_UPDATE: 'edit_note',
      APPOINTMENT_CREATE: 'event',
      APPOINTMENT_UPDATE: 'edit_calendar',
      ENCOUNTER_CREATE: 'swap_horiz',
      PRESCRIPTION_CREATE: 'medication',
      LAB_ORDER_CREATE: 'science',
    };
    return map[eventType] ?? 'history';
  }

  getActivityColor(eventType: string): string {
    if (eventType.includes('CREATE')) return '#059669';
    if (eventType.includes('UPDATE') || eventType.includes('EDIT')) return '#2563eb';
    if (eventType.includes('DELETE') || eventType.includes('REMOVE')) return '#dc2626';
    if (eventType.includes('LOGIN')) return '#7c3aed';
    if (eventType.includes('LOGOUT')) return '#64748b';
    if (eventType.includes('PASSWORD')) return '#d97706';
    return '#64748b';
  }

  formatEventType(eventType: string): string {
    return (eventType ?? '')
      .replaceAll('_', ' ')
      .toLowerCase()
      .replaceAll(/\b\w/g, (c) => c.toUpperCase());
  }

  formatTimestamp(ts: string): string {
    if (!ts) return '';
    const date = new Date(ts);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return 'Just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHrs = Math.floor(diffMin / 60);
    if (diffHrs < 24) return `${diffHrs}h ago`;
    const diffDays = Math.floor(diffHrs / 24);
    if (diffDays < 7) return `${diffDays}d ago`;
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  }
}
