import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  PatientService,
  PatientCreateRequest,
  RegistrationMatch,
} from '../services/patient.service';
import { UserService } from '../services/user.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { deliveryWarningKeys } from '../shared/delivery-warnings';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { RoleContextService } from '../core/role-context.service';
import { ReceptionService, DuplicateCandidate } from '../reception/reception.service';
import { RegistrationService } from '../services/registration.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { switchMap, catchError, debounceTime, distinctUntilChanged, Subject } from 'rxjs';
import { EMPTY, of } from 'rxjs';

@Component({
  selector: 'app-patient-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule],
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
  private readonly receptionService = inject(ReceptionService);
  private readonly registrationService = inject(RegistrationService);
  private readonly translate = inject(TranslateService);

  saving = false;
  hospitals: HospitalResponse[] = [];
  /** true when the logged-in user already has a hospital in their JWT / context */
  hasContextHospital = false;

  // ── MVP 11: Duplicate warning ───────────────────────────────────────────
  duplicateCandidates: DuplicateCandidate[] = [];
  showDuplicateWarning = false;
  private lastDuplicateCheck = '';
  private duplicateCheckSubject = new Subject<{ name: string; dob: string; phone: string }>();

  // ── Cross-hospital existing-patient match (link instead of duplicate) ───
  registrationMatches: RegistrationMatch[] = [];
  /** Staff confirmed "not the same person" — allows a fresh registration. */
  matchDismissed = false;
  linking = false;
  private matchCheckSubject = new Subject<{ email: string; phone: string }>();

  // ── SMS phone verification (IKODDI OTP; hidden when not configured) ─────
  otpAvailable = false;
  otpChallengeId: string | null = null;
  otpMaskedPhone: string | null = null;
  otpCode = '';
  otpSending = false;
  otpConfirming = false;
  phoneVerified = false;
  private otpRequestedForPhone = '';

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

    // Phone verification is optional infrastructure — hide the action entirely
    // when the SMS provider is not configured for this deployment.
    this.patientService.phoneVerificationAvailability().subscribe({
      next: (res) => (this.otpAvailable = res.available),
      error: () => {
        /* silent — treat as unavailable */
      },
    });
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

    // ── MVP 11: debounce duplicate check on name + dob + phone changes ────
    this.duplicateCheckSubject
      .pipe(
        debounceTime(600),
        distinctUntilChanged((a, b) => a.name === b.name && a.dob === b.dob && a.phone === b.phone),
      )
      .subscribe(({ name, dob, phone }) => {
        if (name.length < 3) {
          this.duplicateCandidates = [];
          return;
        }
        this.receptionService
          .getDuplicateCandidates({ name, dob, phone: phone || undefined })
          .subscribe({
            next: (candidates) => {
              this.duplicateCandidates = candidates;
            },
            error: () => {
              /* silent — don't block registration */
            },
          });
      });

    // ── Cross-hospital exact match on email/phone: offer LINK, not duplicate ──
    // switchMap cancels in-flight checks so a stale response can never overwrite
    // the state for the values currently on screen.
    this.matchCheckSubject
      .pipe(
        debounceTime(500),
        distinctUntilChanged((a, b) => a.email === b.email && a.phone === b.phone),
        switchMap(({ email, phone }) => {
          // A new identifier pair re-arms the card: a dismissal only ever
          // applies to the values that were on screen when it was clicked.
          this.matchDismissed = false;
          const phoneQuery = phone.length >= 6 ? phone : '';
          const emailQuery = email.includes('@') ? email : '';
          if (!phoneQuery && !emailQuery) {
            return of([] as RegistrationMatch[]);
          }
          return this.patientService
            .registrationMatch({
              email: emailQuery || undefined,
              phone: phoneQuery || undefined,
              hospitalId: this.form.hospitalId || undefined,
            })
            .pipe(catchError(() => of(null))); // silent — don't block registration
        }),
      )
      .subscribe((matches) => {
        if (matches === null) return; // check failed — keep the previous state
        this.registrationMatches = matches;
      });
  }

  get lockedHospitalName(): string {
    return this.hospitals.length === 1 ? this.hospitals[0].name : 'No hospital assigned';
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  /** Called when first name, last name, date of birth, or primary phone changes. */
  onNameOrDobChange(): void {
    const name = `${this.form.firstName} ${this.form.lastName}`.trim();
    const dob = this.form.dateOfBirth ?? '';
    const phone = (this.form.phoneNumberPrimary ?? '').trim();
    this.duplicateCheckSubject.next({ name, dob, phone });
  }

  /** Called when email or primary phone changes — exact cross-hospital match. */
  onIdentifierChange(): void {
    this.matchCheckSubject.next({
      email: (this.form.email ?? '').trim().toLowerCase(),
      phone: (this.form.phoneNumberPrimary ?? '').trim(),
    });
    // Editing the PHONE invalidates any code sent to the previous number
    // (email edits leave a completed verification intact).
    const phone = (this.form.phoneNumberPrimary ?? '').trim();
    if ((this.phoneVerified || this.otpChallengeId) && phone !== this.otpRequestedForPhone) {
      this.phoneVerified = false;
      this.otpChallengeId = null;
      this.otpMaskedPhone = null;
      this.otpCode = '';
    }
    this.onNameOrDobChange();
  }

  /** Send (or re-send) the SMS verification code to the typed primary phone. */
  sendPhoneVerification(): void {
    const phone = (this.form.phoneNumberPrimary ?? '').trim();
    if (phone.length < 8) {
      this.toast.error(this.translate.instant('PATIENTS.PHONE_TOO_SHORT'));
      return;
    }
    this.otpSending = true;
    this.otpRequestedForPhone = phone;
    this.patientService.requestPhoneVerification(phone).subscribe({
      next: (challenge) => {
        this.otpSending = false;
        this.otpChallengeId = challenge.challengeId;
        this.otpMaskedPhone = challenge.maskedPhone;
        this.otpCode = '';
        this.toast.success(
          this.translate.instant('PATIENTS.CODE_SENT_TO') + ' ' + (challenge.maskedPhone ?? ''),
        );
      },
      error: (err) => {
        this.otpSending = false;
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PATIENTS.CODE_SEND_FAILED'),
        );
      },
    });
  }

  /** Confirm the code the patient read back from their phone. */
  confirmPhoneVerification(): void {
    if (!this.otpChallengeId || this.otpCode.trim().length < 4) {
      return;
    }
    this.otpConfirming = true;
    this.patientService
      .confirmPhoneVerification(this.otpChallengeId, this.otpCode.trim())
      .subscribe({
        next: () => {
          this.otpConfirming = false;
          this.phoneVerified = true;
          this.toast.success(this.translate.instant('PATIENTS.PHONE_VERIFIED'));
        },
        error: (err) => {
          this.otpConfirming = false;
          this.toast.error(
            err?.error?.message ?? this.translate.instant('PATIENTS.PHONE_VERIFY_FAILED'),
          );
        },
      });
  }

  /** Register the matched existing patient at THIS hospital instead of duplicating. */
  linkExistingPatient(match: RegistrationMatch): void {
    if (!this.form.hospitalId) {
      this.toast.error(this.translate.instant('PATIENTS.SELECT_HOSPITAL_FIRST'));
      return;
    }
    this.linking = true;
    this.registrationService
      .create({ patientId: match.patientId, hospitalId: this.form.hospitalId })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('PATIENTS.LINKED_SUCCESS'));
          this.router.navigate(['/patients', match.patientId]);
        },
        error: (err) => {
          this.linking = false;
          const msg =
            err?.status === 409
              ? this.translate.instant('PATIENTS.ALREADY_REGISTERED_HERE')
              : this.translate.instant('PATIENTS.LINK_ERROR');
          this.toast.error(msg);
        },
      });
  }

  /** Staff confirmed the match is a different person (e.g. a shared family phone). */
  dismissMatch(): void {
    this.matchDismissed = true;
  }

  /** Dismiss the duplicate warning and allow submission to continue. */
  proceedDespiteDuplicate(): void {
    this.showDuplicateWarning = false;
    this.submitRegistration();
  }

  onSubmit(): void {
    // Phone-first: email is OPTIONAL — most patients only have a phone number.
    if (
      !this.form.firstName ||
      !this.form.lastName ||
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

    // ── Existing patient matched by email/phone: force an explicit decision ──
    // (link them, or dismiss as "different person" — e.g. a shared family phone)
    if (this.registrationMatches.length > 0 && !this.matchDismissed) {
      this.toast.error(this.translate.instant('PATIENTS.MATCH_DECIDE'));
      return;
    }

    // ── MVP 11: Show duplicate warning if candidates exist ─────────────────
    if (this.duplicateCandidates.length > 0) {
      this.showDuplicateWarning = true;
      return;
    }

    this.submitRegistration();
  }

  private submitRegistration(): void {
    this.saving = true;

    // Step 1: Create a User account with ROLE_PATIENT via admin-register
    // Step 2: Use the returned userId to create the Patient entity
    // If Step 2 fails, delete the orphaned user to avoid dangling accounts.
    const username = this.generateUsername(this.form.firstName, this.form.lastName);
    // Blank email stays OFF the wire (phone-first); a confirmed OTP challenge
    // rides along so the backend can stamp phoneVerifiedAt.
    const email = this.form.email?.trim() || undefined;
    let createdUserId: string | null = null;
    let deliveryWarnings: string[] = [];
    this.userService
      .adminRegister({
        username,
        email,
        firstName: this.form.firstName,
        lastName: this.form.lastName,
        phoneNumber: this.form.phoneNumberPrimary,
        roleNames: ['PATIENT'],
        hospitalId: this.form.hospitalId,
        forcePasswordChange: true,
      })
      .pipe(
        switchMap((user) => {
          createdUserId = user.id;
          this.form.userId = user.id;
          deliveryWarnings = deliveryWarningKeys(user.activationDelivery);
          const payload: PatientCreateRequest = {
            ...this.form,
            email,
            phoneVerificationId:
              this.phoneVerified && this.otpChallengeId ? this.otpChallengeId : undefined,
          };
          return this.patientService.create(payload).pipe(
            catchError((patientErr) => {
              // Compensate: remove the orphaned user account
              this.userService.delete(createdUserId!).subscribe();
              this.toast.error(patientErr?.error?.message ?? 'Failed to register patient');
              this.saving = false;
              return EMPTY;
            }),
          );
        }),
      )
      .subscribe({
        next: (patient) => {
          // The backend now REPORTS what was delivered, so don't claim a
          // channel the report contradicts: with any delivery warning the
          // success toast stays neutral and the warning says what failed.
          const successKey =
            deliveryWarnings.length > 0
              ? 'PATIENTS.REGISTERED_SUCCESS'
              : email
                ? 'PATIENTS.REGISTERED_EMAIL_SENT'
                : this.otpAvailable
                  ? 'PATIENTS.REGISTERED_SMS_SENT'
                  : 'PATIENTS.REGISTERED_SUCCESS';
          this.toast.success(this.translate.instant(successKey));
          for (const key of deliveryWarnings) {
            this.toast.warning(this.translate.instant(key));
          }
          this.router.navigate(['/patients', patient.id]);
        },
        error: (err) => {
          this.toast.error(err?.error?.message ?? 'Failed to register patient');
          this.saving = false;
        },
      });
  }

  /** Generate a short username from first + last name (e.g. tou4821) */
  private generateUsername(first: string, last: string): string {
    const base = (first.charAt(0) + last.substring(0, 2)).toLowerCase().replace(/[^a-z]/g, '');
    const array = new Uint32Array(1);
    crypto.getRandomValues(array);
    const suffix = 1000 + (array[0] % 9000);
    return `${base}${suffix}`;
  }
}
