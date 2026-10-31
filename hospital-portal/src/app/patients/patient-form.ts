import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { DatePipe } from '@angular/common';
import { Component, ElementRef, inject, OnInit, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  PatientService,
  PatientCreateRequest,
  RegistrationMatch,
  RegistrationMatchQuery,
} from '../services/patient.service';
import { UserService } from '../services/user.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import {
  deliveryWarningKeys,
  hasActivationSent,
  NotificationDeliveryStatus,
} from '../shared/delivery-warnings';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { RoleContextService } from '../core/role-context.service';
import { ReceptionService, DuplicateCandidate } from '../reception/reception.service';
import { RegistrationService } from '../services/registration.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { switchMap, catchError, debounceTime, distinctUntilChanged, map, Subject } from 'rxjs';
import { of } from 'rxjs';
import { labelledRefusalReason } from '../shared/refusal-reason';
import { blankFields, focusFirstInvalid } from '../shared/forms/invalid-fields';

/**
 * QA D1118: the card names what the match is really on. A record found by the
 * same name and date of birth (matchedOn IDENTITY) was described as "a record
 * with this phone number or e-mail", which the receptionist had not typed.
 * '' = phone or e-mail, '_IDENTITY' = name and date of birth, '_BOTH' = some of each.
 */
/** QA D1118: the card's sentence by match basis, then by where the records are. */
export const MATCH_DESCRIPTION_KEYS: Record<string, Record<string, string>> = {
  '': {
    '': 'PATIENTS.EXISTING_MATCH_DESC',
    _HERE: 'PATIENTS.EXISTING_MATCH_DESC_HERE',
    _MIXED: 'PATIENTS.EXISTING_MATCH_DESC_MIXED',
  },
  _IDENTITY: {
    '': 'PATIENTS.EXISTING_MATCH_DESC_IDENTITY',
    _HERE: 'PATIENTS.EXISTING_MATCH_DESC_IDENTITY_HERE',
    _MIXED: 'PATIENTS.EXISTING_MATCH_DESC_IDENTITY_MIXED',
  },
  _BOTH: {
    '': 'PATIENTS.EXISTING_MATCH_DESC_BOTH',
    _HERE: 'PATIENTS.EXISTING_MATCH_DESC_BOTH_HERE',
    _MIXED: 'PATIENTS.EXISTING_MATCH_DESC_BOTH_MIXED',
  },
};

export function matchBasisSuffix(matches: readonly Pick<RegistrationMatch, 'matchedOn'>[]): string {
  const identity = matches.filter((m) => m.matchedOn === 'IDENTITY').length;
  if (identity === 0) return '';
  return identity === matches.length ? '_IDENTITY' : '_BOTH';
}

@Component({
  selector: 'app-patient-form',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule, DatePipe, ServerTimePipe],
  templateUrl: './patient-form.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './patient-form.scss',
})
export class PatientFormComponent implements OnInit {
  /** QA D1037: the server's reason per refused field, shown under the field. */
  serverFieldErrors: Record<string, string> = {};

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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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

  /**
   * QA D986: the match card said "exists in another hospital" next to a
   * "Déjà enregistré dans cet hôpital" badge. The sentence now says where the
   * matched records are: here, elsewhere, or both.
   */
  matchDescriptionKey(): string {
    const matches = this.identityMatches;
    const here = matches.filter((m) => m.alreadyRegisteredHere).length;
    let where = '_MIXED';
    if (here === 0) where = '';
    else if (here === matches.length) where = '_HERE';
    return MATCH_DESCRIPTION_KEYS[matchBasisSuffix(matches)][where];
  }
  /**
   * QA D1061 / D1064: the matches that may be this same person (the same name
   * and date of birth, or a request without them) — the card that asks to
   * link or to dismiss.
   */
  get identityMatches(): RegistrationMatch[] {
    return this.registrationMatches.filter((m) => !this.isSharedContact(m));
  }

  /**
   * QA D1061: another patient who only shares the typed PHONE and was born
   * another year (a child registered under a parent's number): said once, no
   * decision forced. An e-mail stays one person's (the server refuses a shared
   * one), and a phone match born the same year may be this person with the
   * name spelled differently, so both still ask to link or dismiss.
   */
  get sharedContactMatches(): RegistrationMatch[] {
    return this.registrationMatches.filter((m) => this.isSharedContact(m));
  }

  private isSharedContact(m: RegistrationMatch): boolean {
    const year = Number((this.form.dateOfBirth ?? '').slice(0, 4));
    return (
      m.sameIdentity === false &&
      m.matchedOn === 'PHONE' &&
      (!year || m.birthYear == null || m.birthYear !== year)
    );
  }

  /** Staff confirmed "not the same person" — allows a fresh registration. */
  matchDismissed = false;
  linking = false;
  /** QA D756: the link failure stays on the card, not only in a toast. */
  linkError: string | null = null;
  private matchCheckSubject = new Subject<MatchQuery>();

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
    ethnicity: '',
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
        error: () => this.toast.error(this.translate.instant('PATIENTS.HOSPITALS_LOAD_FAILED')),
      });
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals = [h];
          this.form.hospitalId = h.id;
        },
        error: () => this.toast.error(this.translate.instant('PATIENTS.HOSPITAL_LOAD_FAILED')),
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
        distinctUntilChanged(
          (a, b) =>
            a.email === b.email &&
            a.phone === b.phone &&
            a.firstName === b.firstName &&
            a.lastName === b.lastName &&
            a.dateOfBirth === b.dateOfBirth,
        ),
        switchMap((q) => {
          // A new identifier pair re-arms the card: a dismissal only ever
          // applies to the values that were on screen when it was clicked.
          this.matchDismissed = false;
          const params = this.matchParams(q);
          if (!params) {
            return of([] as RegistrationMatch[]);
          }
          return this.patientService.registrationMatch(params).pipe(catchError(() => of(null))); // silent — don't block registration
        }),
      )
      .subscribe((matches) => {
        if (matches === null) return; // check failed — keep the previous state
        this.registrationMatches = matches;
      });
  }

  get lockedHospitalName(): string {
    return this.hospitals.length === 1
      ? this.hospitals[0].name
      : this.translate.instant('COMMON.NO_HOSPITAL_ASSIGNED');
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  /**
   * QA D1061: the phone the registration will carry — the patient's own, or,
   * left blank for a child, the parent's or guardian's.
   */
  get effectivePhone(): string {
    return (
      (this.form.phoneNumberPrimary ?? '').trim() || (this.form.emergencyContactPhone ?? '').trim()
    );
  }

  /** QA D1061: the patient's own phone is required only when no guardian phone is given. */
  get phoneRequired(): boolean {
    return !(this.form.emergencyContactPhone ?? '').trim();
  }

  /** Called when first name, last name, date of birth, or primary phone changes. */
  onNameOrDobChange(): void {
    const name = `${this.form.firstName} ${this.form.lastName}`.trim();
    const dob = this.form.dateOfBirth ?? '';
    const phone = this.effectivePhone;
    this.duplicateCheckSubject.next({ name, dob, phone });
    // QA D1064: the same name and date of birth at another hospital is a match
    // even when the phone differs, so the identity check runs on these too.
    this.matchCheckSubject.next(this.currentMatchQuery());
  }

  /** QA D1061: the guardian's phone stands in for a child's blank one. */
  onGuardianPhoneChange(): void {
    if (!(this.form.phoneNumberPrimary ?? '').trim()) {
      this.onNameOrDobChange();
    }
  }

  private currentMatchQuery(): MatchQuery {
    return {
      email: (this.form.email ?? '').trim().toLowerCase(),
      phone: this.effectivePhone,
      firstName: (this.form.firstName ?? '').trim(),
      lastName: (this.form.lastName ?? '').trim(),
      dateOfBirth: this.form.dateOfBirth ?? '',
    };
  }

  /**
   * The registration-match request for what is typed, or null when nothing is
   * specific enough to look up: a phone of 6+ characters, an e-mail, or the
   * full name with the date of birth (QA D1064).
   */
  private matchParams(q: MatchQuery): RegistrationMatchQuery | null {
    const phone = q.phone.length >= 6 ? q.phone : '';
    const email = q.email.includes('@') ? q.email : '';
    const identity = !!(q.firstName && q.lastName && /^\d{4}-\d{2}-\d{2}$/.test(q.dateOfBirth));
    if (!phone && !email && !identity) return null;
    const params: RegistrationMatchQuery = {
      email: email || undefined,
      phone: phone || undefined,
      hospitalId: this.form.hospitalId || undefined,
    };
    if (identity) {
      params.firstName = q.firstName;
      params.lastName = q.lastName;
      params.dateOfBirth = q.dateOfBirth;
    }
    return params;
  }

  /** Called when email or primary phone changes — exact cross-hospital match. */
  onIdentifierChange(): void {
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
    this.linkError = null;
    this.registrationService
      .create({ patientId: match.patientId, hospitalId: this.form.hospitalId })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('PATIENTS.LINKED_SUCCESS'));
          this.router.navigate(['/patients', match.patientId]);
        },
        error: (err) => {
          this.linking = false;
          const serverMessage: unknown = err?.error?.message;
          const fallback =
            err?.status === 409
              ? this.translate.instant('PATIENTS.ALREADY_REGISTERED_HERE')
              : this.translate.instant('PATIENTS.LINK_ERROR');
          const msg =
            typeof serverMessage === 'string' && serverMessage.trim() ? serverMessage : fallback;
          this.linkError = msg;
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
    // QA D1061: a child is registered under the parent's or guardian's number:
    // with the patient's own phone left blank, the guardian's is used.
    // The form itself is left as typed: the guardian's number goes into the
    // request only (submitRegistration), so a refused submit shows no
    // number the desk never typed in that field.
    // Phone-first: email is OPTIONAL — most patients only have a phone number.
    // QA D1072: every missing field is marked under itself, and the first one
    // is focused and scrolled to; the toast alone named none of them.
    const missing = blankFields(
      { ...this.form, phoneNumberPrimary: this.effectivePhone } as unknown as Record<
        string,
        unknown
      >,
      PATIENT_FORM_REQUIRED.filter((f) => f !== 'hospitalId' || !this.hospitalLocked),
    );
    if (missing.length > 0 || !this.form.hospitalId) {
      const required = this.translate.instant('COMMON.FIELD_REQUIRED');
      this.serverFieldErrors = Object.fromEntries(missing.map((f) => [f, required]));
      focusFirstInvalid(
        this.host.nativeElement,
        missing.map((f) => PATIENT_FORM_FIELD_IDS[f] ?? f),
      );
      this.toast.error(
        this.translate.instant(
          missing.length > 0 ? 'PATIENTS.REQUIRED_FIELDS' : 'PATIENTS.SELECT_HOSPITAL_FIRST',
        ),
      );
      return;
    }
    this.serverFieldErrors = {};

    // ── Existing patient matched by email/phone/identity: force an explicit decision ──
    // (link them, or dismiss as "different person"). QA D1061: a match that
    // only shares the contact (sameIdentity === false) is not asked about.
    if (this.identityMatches.length > 0 && !this.matchDismissed) {
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
    this.serverFieldErrors = {};

    // QA D626: ONE call registers the account and the patient record, in one
    // server transaction, so a failure leaves neither behind (no client-side
    // compensation delete, which a closed tab or a dropped connection skipped).
    const username = this.generateUsername(this.form.firstName, this.form.lastName);
    // Blank email stays OFF the wire (phone-first); a confirmed OTP challenge
    // rides along so the backend can stamp phoneVerifiedAt.
    const email = this.form.email?.trim() || undefined;
    let deliveryWarnings: string[] = [];
    let deliveryReport: NotificationDeliveryStatus[] = [];
    const patient: PatientCreateRequest = {
      ...this.form,
      phoneNumberPrimary: this.effectivePhone,
      email,
      phoneVerificationId:
        this.phoneVerified && this.otpChallengeId ? this.otpChallengeId : undefined,
    };
    this.userService
      .adminRegister({
        username,
        email,
        firstName: this.form.firstName,
        lastName: this.form.lastName,
        phoneNumber: this.effectivePhone,
        roleNames: ['PATIENT'],
        hospitalId: this.form.hospitalId,
        forcePasswordChange: true,
        patientRegistration: true,
        patient,
      })
      .pipe(
        map((user) => {
          this.form.userId = user.id;
          deliveryReport = user.activationDelivery ?? [];
          deliveryWarnings = deliveryWarningKeys(deliveryReport);
          return { id: user.patientId };
        }),
      )
      .subscribe({
        next: (patient) => {
          // Claim a channel ONLY when the report proves that channel's
          // activation message was SENT. An empty report means nothing was
          // attempted (e.g. re-registering an identity that already has its
          // assignment) — the old entered-email/OTP-config heuristic turned
          // exactly that into a false "email sent" claim.
          const successKey = hasActivationSent(deliveryReport, 'EMAIL')
            ? 'PATIENTS.REGISTERED_EMAIL_SENT'
            : hasActivationSent(deliveryReport, 'SMS')
              ? 'PATIENTS.REGISTERED_SMS_SENT'
              : 'PATIENTS.REGISTERED_SUCCESS';
          this.toast.success(this.translate.instant(successKey));
          for (const key of deliveryWarnings) {
            this.toast.warning(this.translate.instant(key));
          }
          this.router.navigate(['/patients', patient.id]);
        },
        error: (err) => {
          // QA D1037 / D984: the refused field is named, in the toast and
          // under the field; the generic "invalid parameters" said neither.
          this.serverFieldErrors = fieldErrorsOf(err);
          this.toast.error(
            labelledRefusalReason(err, this.translate, PATIENT_FORM_FIELD_LABELS) ??
              this.translate.instant('PATIENTS.REGISTER_FAILED'),
          );
          this.saving = false;
          // QA D725: the server refuses an e-mail/phone that already belongs to
          // a patient (the debounced check may not have answered before the
          // click). Fetch the match now so the card offers the existing record.
          if (err?.status === 409) {
            this.refreshMatchesNow();
          }
        },
      });
  }

  private refreshMatchesNow(): void {
    const params = this.matchParams(this.currentMatchQuery());
    if (!params) return;
    this.patientService
      .registrationMatch(params)
      .pipe(catchError(() => of(null)))
      .subscribe((matches) => {
        if (matches && matches.length > 0) {
          this.registrationMatches = matches;
          this.matchDismissed = false;
        }
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

/** The query the cross-hospital match runs on (QA D1061 / D1064: identity too). */
interface MatchQuery {
  email: string;
  phone: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
}

/** QA D1072: the fields the browser-side check requires, in reading order. */
export const PATIENT_FORM_REQUIRED = [
  'firstName',
  'lastName',
  'phoneNumberPrimary',
  'dateOfBirth',
  'gender',
  'hospitalId',
  'country',
  'city',
] as const;

/** QA D1072: a form field's element id, where it differs from the field name. */
export const PATIENT_FORM_FIELD_IDS: Readonly<Record<string, string>> = {
  phoneNumberPrimary: 'phonePrimary',
  dateOfBirth: 'dob',
  hospitalId: 'hospital',
};

/** QA D1037: the form's fields, as the server names them, to their label keys. */
export const PATIENT_FORM_FIELD_LABELS: Readonly<Record<string, string>> = {
  firstName: 'PATIENTS.FIRST_NAME',
  lastName: 'PATIENTS.LAST_NAME',
  email: 'PATIENTS.EMAIL',
  phoneNumberPrimary: 'PATIENTS.PHONE',
  phoneNumberSecondary: 'PATIENTS.PHONE_SECONDARY',
  dateOfBirth: 'PATIENTS.DOB',
};

/** The per-field reasons of a validation refusal, or none. */
export function fieldErrorsOf(err: unknown): Record<string, string> {
  const body = (err as { error?: { fieldErrors?: unknown } } | null)?.error;
  const raw = body?.fieldErrors;
  if (!raw || typeof raw !== 'object') return {};
  return Object.fromEntries(
    Object.entries(raw as Record<string, unknown>).filter(
      (e): e is [string, string] => typeof e[1] === 'string' && e[1].trim() !== '',
    ),
  );
}
