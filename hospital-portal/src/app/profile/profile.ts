import {
  Component,
  inject,
  OnInit,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { MfaService, MfaEnrollmentResponse } from '../auth/mfa.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  ProfileService,
  UserProfile,
  CredentialHealth,
  Assignment,
  AuditEvent,
  ProfileUpdateRequest,
  RecoveryContact,
} from '../services/profile.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

type ProfileTab = 'overview' | 'edit' | 'security' | 'activity';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './profile.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './profile.scss',
})
export class ProfileComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly mfaService = inject(MfaService);
  private readonly profileService = inject(ProfileService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly translate = inject(TranslateService);

  private static readonly VALID_TABS: readonly ProfileTab[] = [
    'overview',
    'edit',
    'security',
    'activity',
  ];

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
      ['ROLE_SUPER_ADMIN', 'DASHBOARD.ROLE.SUPER_ADMIN'],
      ['ROLE_HOSPITAL_ADMIN', 'DASHBOARD.ROLE.HOSPITAL_ADMIN'],
      ['ROLE_ADMIN', 'PROFILE.TYPE_ADMINISTRATOR'],
      ['ROLE_DOCTOR', 'DASHBOARD.ROLE.DOCTOR'],
      ['ROLE_NURSE', 'DASHBOARD.ROLE.NURSE'],
      ['ROLE_MIDWIFE', 'DASHBOARD.ROLE.MIDWIFE'],
      ['ROLE_RECEPTIONIST', 'DASHBOARD.ROLE.RECEPTIONIST'],
      ['ROLE_LAB_SCIENTIST', 'DASHBOARD.ROLE.LAB_SCIENTIST'],
      ['ROLE_STAFF', 'DASHBOARD.ROLE.STAFF'],
      ['ROLE_PATIENT', 'DASHBOARD.ROLE.PATIENT'],
    ];
    for (const [role, labelKey] of priority) {
      if (roles.includes(role)) return this.translate.instant(labelKey);
    }
    return this.translate.instant('PROFILE.TYPE_USER');
  });

  /** Show license number only for clinical staff roles in this session. */
  showLicenseNumber = computed(() => {
    const clinicalRoles = ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST'];
    return this.auth.hasAnyRole(clinicalRoles) && !!this.user()?.licenseNumber;
  });

  memberSince = computed(() => {
    const u = this.user();
    if (!u?.createdAt) return '';
    return new Date(u.createdAt).toLocaleDateString(this.dateLocale(), {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  });

  lastLogin = computed(() => {
    const u = this.user();
    if (!u?.lastLoginAt) return this.translate.instant('PROFILE.NEVER');
    return new Date(u.lastLoginAt).toLocaleString(this.dateLocale(), {
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
    if (c.verifiedRecoveryContacts > 0) score += 20;
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
    if (s >= 80) return this.translate.instant('PROFILE.SCORE_EXCELLENT');
    if (s >= 50) return this.translate.instant('PROFILE.SCORE_GOOD');
    return this.translate.instant('PROFILE.SCORE_NEEDS_ATTENTION');
  });

  accountAge = computed(() => {
    const u = this.user();
    if (!u?.createdAt) return '';
    const created = new Date(u.createdAt);
    const now = new Date();
    const diffMs = now.getTime() - created.getTime();
    const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    if (days < 1) return this.translate.instant('PROFILE.AGE_TODAY');
    if (days < 30) {
      return this.translate.instant(days > 1 ? 'PROFILE.AGE_DAYS' : 'PROFILE.AGE_DAY', {
        count: days,
      });
    }
    const months = Math.floor(days / 30);
    if (months < 12) {
      return this.translate.instant(months > 1 ? 'PROFILE.AGE_MONTHS' : 'PROFILE.AGE_MONTH', {
        count: months,
      });
    }
    const years = Math.floor(months / 12);
    return this.translate.instant(years > 1 ? 'PROFILE.AGE_YEARS' : 'PROFILE.AGE_YEAR', {
      count: years,
    });
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
        this.mfaError.set(
          err?.error?.message ?? this.translate.instant('PROFILE.MFA_ENROLL_FAILED'),
        );
        this.mfaLoading.set(false);
      },
    });
  }

  verifyMfaCode(): void {
    if (!this.mfaTotpCode || this.mfaTotpCode.length < 6) {
      this.mfaError.set(this.translate.instant('PROFILE.MFA_CODE_LENGTH'));
      return;
    }
    this.mfaLoading.set(true);
    this.mfaError.set('');
    this.mfaService.verifyEnrollment(this.mfaTotpCode).subscribe({
      next: () => {
        this.mfaStep.set('backup');
        this.mfaLoading.set(false);
        this.toast.success(this.translate.instant('PROFILE.MFA_ENROLLED'));
        // Refresh credential health to update the MFA status display
        this.profileService.getCredentialHealth().subscribe({
          next: (creds) => this.credentials.set(creds),
        });
      },
      error: (err) => {
        this.mfaError.set(
          err?.error?.message ?? this.translate.instant('PROFILE.MFA_CODE_REJECTED'),
        );
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
    this.toast.success(this.translate.instant('PROFILE.BACKUP_CODES_COPIED'));
  }

  /* ── Recovery Contacts ── */
  showAddRecovery = signal(false);
  newRecoveryType = signal<'EMAIL' | 'PHONE'>('EMAIL');
  newRecoveryValue = signal('');
  newRecoveryPrimary = signal(false);
  recoverySaving = signal(false);
  recoveryError = signal('');

  /* ── Recovery Contact Verification ── */
  verifyingContactId = signal<string | null>(null);
  verificationCode = signal('');
  verificationSending = signal(false);
  verificationError = signal('');
  verificationCodeSent = signal(false);

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
      this.recoveryError.set(this.translate.instant('PROFILE.RECOVERY_VALUE_REQUIRED'));
      return;
    }
    this.recoverySaving.set(true);
    this.recoveryError.set('');

    const existing = (this.credentials()?.recoveryContacts ?? []).map((rc) => ({
      contactType: rc.contactType,
      contactValue: rc.contactValue,
      primaryContact: this.newRecoveryPrimary() ? false : rc.primaryContact,
    }));

    const payload = [
      ...existing,
      {
        contactType: this.newRecoveryType(),
        contactValue: value,
        primaryContact: this.newRecoveryPrimary(),
      },
    ];

    this.profileService.updateRecoveryContacts(payload).subscribe({
      next: () => {
        this.recoverySaving.set(false);
        this.showAddRecovery.set(false);
        this.toast.success(this.translate.instant('PROFILE.RECOVERY_ADDED'));
        this.profileService.getCredentialHealth().subscribe({
          next: (creds) => this.credentials.set(creds),
        });
      },
      error: (err) => {
        this.recoverySaving.set(false);
        this.recoveryError.set(
          err?.error?.message ?? this.translate.instant('PROFILE.RECOVERY_SAVE_FAILED'),
        );
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
        primaryContact: rc.primaryContact,
      }));

    this.profileService.updateRecoveryContacts(remaining).subscribe({
      next: () => {
        this.recoverySaving.set(false);
        this.toast.success(this.translate.instant('PROFILE.RECOVERY_REMOVED'));
        this.profileService.getCredentialHealth().subscribe({
          next: (creds) => this.credentials.set(creds),
        });
      },
      error: (err) => {
        this.recoverySaving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PROFILE.RECOVERY_REMOVE_FAILED'),
        );
      },
    });
  }

  sendVerificationCode(contact: RecoveryContact): void {
    if (!contact.id) return;
    this.verifyingContactId.set(contact.id);
    this.verificationCode.set('');
    this.verificationError.set('');
    this.verificationSending.set(true);
    this.verificationCodeSent.set(false);

    this.profileService.sendRecoveryVerificationCode(contact.id).subscribe({
      next: () => {
        this.verificationSending.set(false);
        this.verificationCodeSent.set(true);
        this.toast.success(
          this.translate.instant('PROFILE.VERIFICATION_CODE_SENT', {
            contact: contact.contactValue,
          }),
        );
      },
      error: (err) => {
        this.verificationSending.set(false);
        this.verificationError.set(
          err?.error?.message ?? this.translate.instant('PROFILE.VERIFICATION_SEND_FAILED'),
        );
      },
    });
  }

  submitVerificationCode(): void {
    const contactId = this.verifyingContactId();
    const code = this.verificationCode().trim();
    if (!contactId || !code) return;

    this.verificationSending.set(true);
    this.verificationError.set('');

    this.profileService.verifyRecoveryContact(contactId, code).subscribe({
      next: () => {
        this.verificationSending.set(false);
        this.verifyingContactId.set(null);
        this.verificationCodeSent.set(false);
        this.toast.success(this.translate.instant('PROFILE.RECOVERY_VERIFIED'));
        this.profileService.getCredentialHealth().subscribe({
          next: (creds) => this.credentials.set(creds),
        });
      },
      error: (err) => {
        this.verificationSending.set(false);
        this.verificationError.set(
          err?.error?.message ?? this.translate.instant('PROFILE.VERIFICATION_FAILED'),
        );
      },
    });
  }

  cancelVerification(): void {
    this.verifyingContactId.set(null);
    this.verificationCode.set('');
    this.verificationError.set('');
    this.verificationCodeSent.set(false);
  }

  /* ── Lifecycle ── */
  ngOnInit(): void {
    // Honor /profile?tab=security|edit|activity from the Settings hub so
    // the shortcuts there land on the requested tab instead of always on
    // the Overview view.
    this.route.queryParamMap.subscribe((params) => {
      const requested = params.get('tab') as ProfileTab | null;
      if (requested && ProfileComponent.VALID_TABS.includes(requested)) {
        this.activeTab.set(requested);
      }
    });
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
      this.toast.error(this.translate.instant('PROFILE.LOAD_FAILED'));
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
        this.toast.success(this.translate.instant('PROFILE.UPDATED'));

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
        this.toast.error(err?.error?.message ?? this.translate.instant('PROFILE.UPDATE_FAILED'));
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
      this.toast.error(this.translate.instant('PROFILE.PHOTO_TOO_LARGE'));
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.toast.error(this.translate.instant('PROFILE.PHOTO_NOT_IMAGE'));
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
        this.toast.success(this.translate.instant('PROFILE.PHOTO_UPDATED'));
      },
      error: (err) => {
        this.uploadingAvatar.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PROFILE.PHOTO_UPLOAD_FAILED'),
        );
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
        this.toast.success(this.translate.instant('PROFILE.PHOTO_REMOVED'));
      },
      error: () => {
        this.uploadingAvatar.set(false);
        this.toast.error(this.translate.instant('PROFILE.PHOTO_REMOVE_FAILED'));
      },
    });
  }

  /* ── Security Actions ── */
  requestPasswordReset(): void {
    const u = this.user();
    if (!u?.email) {
      this.toast.error(this.translate.instant('PROFILE.NO_EMAIL_ON_FILE'));
      return;
    }
    this.profileService.requestPasswordReset(u.email).subscribe({
      next: () => this.toast.success(this.translate.instant('PROFILE.RESET_LINK_SENT')),
      error: () => this.toast.error(this.translate.instant('PROFILE.RESET_REQUEST_FAILED')),
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
    if (eventType.includes('UPDATE') || eventType.includes('EDIT')) return '#0e7c6b';
    if (eventType.includes('DELETE') || eventType.includes('REMOVE')) return '#dc2626';
    if (eventType.includes('LOGIN')) return '#7c3aed';
    if (eventType.includes('LOGOUT')) return '#64748b';
    if (eventType.includes('PASSWORD')) return '#d97706';
    return '#64748b';
  }

  /**
   * Locale-aware audit-event label. Same three-tier lookup as
   * {@link EnumLabelPipe}: `PORTAL.ENUM.AUDIT_EVENT_TYPE.*` first, then a
   * prettified fallback so an unmapped event never renders as a raw key.
   */
  formatEventType(eventType: string): string {
    if (!eventType) return '';
    const key = `PORTAL.ENUM.AUDIT_EVENT_TYPE.${eventType}`;
    const label = this.translate.instant(key);
    if (typeof label === 'string' && label && label !== key) return label;
    return eventType
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
    if (diffMin < 1) return this.translate.instant('PROFILE.TIME_JUST_NOW');
    if (diffMin < 60) {
      return this.translate.instant('PROFILE.TIME_MINUTES_AGO', { count: diffMin });
    }
    const diffHrs = Math.floor(diffMin / 60);
    if (diffHrs < 24) {
      return this.translate.instant('PROFILE.TIME_HOURS_AGO', { count: diffHrs });
    }
    const diffDays = Math.floor(diffHrs / 24);
    if (diffDays < 7) {
      return this.translate.instant('PROFILE.TIME_DAYS_AGO', { count: diffDays });
    }
    return date.toLocaleDateString(this.dateLocale(), {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  }

  /**
   * BCP-47 tag for `Intl` date formatting - the active UI language, never a
   * hard-coded 'en-US'. Falls back the same way {@link EnumLabelPipe} does so
   * the first paint (before a language is set) still formats.
   */
  private dateLocale(): string {
    return this.translate.getCurrentLang() || this.translate.getFallbackLang() || 'en';
  }
}
