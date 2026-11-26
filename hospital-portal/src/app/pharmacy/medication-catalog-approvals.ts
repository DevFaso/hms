import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  DoseLimitSnapshot,
  FormularySnapshot,
  MedicationCatalogItemResponse,
  MedicationCatalogVersion,
  PharmacyService,
} from '../services/pharmacy.service';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { KeyLabelPipe } from '../shared/pipes/key-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refusalReason } from '../shared/refusal-reason';

/** One line of a version: a field, what was in force and what is proposed. */
export interface VersionLine {
  field: string;
  before: string;
  after: string;
  changed: boolean;
}

/** What a formulary version carries, in the order a pharmacist reads an entry. */
const FORMULARY_FIELDS: readonly (keyof FormularySnapshot)[] = [
  'nameFr',
  'genericName',
  'brandName',
  'atcCode',
  'form',
  'strength',
  'strengthUnit',
  'route',
  'rxnormCode',
  'tallManName',
  'controlled',
  'active',
];

/** What a new entry also carries (edited directly afterwards, so never part of a later version). */
const NEW_ENTRY_FIELDS: readonly (keyof FormularySnapshot)[] = [
  'category',
  'essentialList',
  'description',
];

const DOSE_FIELDS: readonly (keyof DoseLimitSnapshot)[] = [
  'maxDoseMgPerKg',
  'maxDailyMgPerKg',
  'maxSingleDoseMg',
  'maxDailyMg',
  'manualReviewUnderMonths',
  'weightBands',
  'source',
];

/**
 * Go-live D2 (V385): the approval circuit of the medication catalogue, as the pharmacy reads it.
 *
 * Without an entry: the hospital's queue (versions awaiting approval) and its history (every
 * version, decided or not). With an entry: that entry's versions, the latest first.
 *
 * A version shows what is proposed beside what was in force, who proposed it, when and why, and
 * what became of it. Approve and refuse are offered only where the server says this user may
 * (the right role at this hospital, and never their own proposal); a refusal asks its reason.
 */
@Component({
  selector: 'app-medication-catalog-approvals',
  standalone: true,
  imports: [
    DateTimePipe,
    FormsModule,
    KeyLabelPipe,
    TranslateModule,
    ServerTimePipe,
    SubmitGuardDirective,
  ],
  templateUrl: './medication-catalog-approvals.html',
  styleUrl: './medication-catalog-approvals.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class MedicationCatalogApprovalsComponent {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** The entry whose history is shown; none for the hospital's queue. */
  readonly entry = input<MedicationCatalogItemResponse | null>(null);
  /** Bumped by the host to have the versions read again (a version was just proposed). */
  readonly reloadKey = input(0);
  /** A version was approved, refused or withdrawn: the catalogue and the counts may have moved. */
  readonly decided = output<void>();
  /** How many versions await approval, each time the queue is read. */
  readonly pendingCount = output<number>();

  readonly view = signal<'PENDING' | 'HISTORY'>('PENDING');
  readonly versions = signal<MedicationCatalogVersion[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly acting = signal<string | null>(null);
  private readVersion = 0;

  /** The version being refused, and the reason typed so far. */
  readonly refusing = signal<string | null>(null);
  refusalText = '';
  readonly refusalMissing = signal(false);

  readonly forOneEntry = computed(() => !!this.entry());

  /** What an empty list says: no version for this entry, none waiting, or none at all. */
  readonly emptyKey = computed(() => {
    if (this.forOneEntry()) return 'PHARMACY.CATALOG_APPROVAL.EMPTY_ENTRY';
    return this.view() === 'PENDING'
      ? 'PHARMACY.CATALOG_APPROVAL.EMPTY_PENDING'
      : 'PHARMACY.CATALOG_APPROVAL.EMPTY_HISTORY';
  });

  constructor() {
    // Reads again whenever the entry or the view changes.
    effect(() => {
      this.entry();
      this.view();
      this.reloadKey();
      this.load();
    });
  }

  show(view: 'PENDING' | 'HISTORY'): void {
    this.view.set(view);
  }

  load(): void {
    const version = ++this.readVersion;
    this.loading.set(true);
    this.loadFailed.set(false);
    const entry = this.entry();
    const pending = !entry && this.view() === 'PENDING';
    const failed = () => {
      if (version !== this.readVersion) return;
      this.versions.set([]);
      this.loadFailed.set(true);
      this.loading.set(false);
    };
    if (entry) {
      this.svc.catalogVersionHistory(entry.id).subscribe({
        next: (list) => {
          if (version !== this.readVersion) return;
          this.versions.set(list ?? []);
          this.loading.set(false);
        },
        error: failed,
      });
      return;
    }
    this.svc.listCatalogVersions(pending ? 'PENDING_APPROVAL' : null).subscribe({
      next: (page) => {
        if (version !== this.readVersion) return;
        this.versions.set(page?.content ?? []);
        this.loading.set(false);
        if (pending) {
          this.pendingCount.emit(page?.totalElements ?? 0);
        }
      },
      error: failed,
    });
  }

  /** The lines of a version: every field for a new entry, else what it carries, changes marked. */
  lines(version: MedicationCatalogVersion): VersionLine[] {
    const changed = new Set(version.changedFields ?? []);
    if (version.kind === 'DOSE_LIMIT') {
      const after = version.doseLimits ?? {};
      const before = version.previousDoseLimits ?? {};
      return DOSE_FIELDS.map((field) => ({
        field,
        before: this.doseValue(before, field),
        after: this.doseValue(after, field),
        changed: changed.has(field),
      })).filter((line) => line.changed || line.after !== '—' || line.before !== '—');
    }
    const after = version.formulary ?? {};
    const before = version.previousFormulary;
    const fields =
      version.changeType === 'CREATE'
        ? [...FORMULARY_FIELDS, ...NEW_ENTRY_FIELDS]
        : FORMULARY_FIELDS;
    return fields
      .map((field) => ({
        field,
        before: before ? this.formularyValue(before, field) : '—',
        after: this.formularyValue(after, field),
        changed: changed.has(field),
      }))
      .filter((line) => line.changed || line.after !== '—' || line.before !== '—');
  }

  private formularyValue(snapshot: FormularySnapshot, field: keyof FormularySnapshot): string {
    const value = snapshot[field];
    if (typeof value === 'boolean') {
      return this.translate.instant(value ? 'COMMON.YES' : 'COMMON.NO');
    }
    return value == null || value === '' ? '—' : String(value);
  }

  private doseValue(snapshot: DoseLimitSnapshot, field: keyof DoseLimitSnapshot): string {
    if (field === 'weightBands') {
      const bands = snapshot.weightBands ?? [];
      if (bands.length === 0) return '—';
      return bands
        .map((band) =>
          this.translate.instant('PHARMACY.CATALOG_APPROVAL.BAND', {
            min: band.minWeightKg ?? '0',
            max: band.maxWeightKg ?? '∞',
            dose: band.dosePerAdministration ?? '—',
            unit: band.doseUnit ?? '',
            perDay: band.maxDosesPerDay ?? '—',
          }),
        )
        .join(' ; ');
    }
    const value = snapshot[field];
    return value == null || value === '' ? '—' : String(value);
  }

  approve(version: MedicationCatalogVersion): void {
    this.act(version, this.svc.approveCatalogVersion(version.id), 'APPROVED_TOAST');
  }

  withdraw(version: MedicationCatalogVersion): void {
    this.act(version, this.svc.withdrawCatalogVersion(version.id), 'WITHDRAWN_TOAST');
  }

  startRefusal(version: MedicationCatalogVersion): void {
    this.refusalText = '';
    this.refusalMissing.set(false);
    this.refusing.set(version.id);
  }

  cancelRefusal(): void {
    this.refusing.set(null);
    this.refusalMissing.set(false);
  }

  confirmRefusal(version: MedicationCatalogVersion): void {
    const reason = this.refusalText.trim();
    if (!reason) {
      this.refusalMissing.set(true);
      this.toast.error(this.translate.instant('PHARMACY.CATALOG_APPROVAL.REFUSE_REASON_REQUIRED'));
      return;
    }
    this.refusalMissing.set(false);
    this.act(version, this.svc.rejectCatalogVersion(version.id, reason), 'REFUSED_TOAST');
  }

  private act(
    version: MedicationCatalogVersion,
    call$: ReturnType<PharmacyService['approveCatalogVersion']>,
    toastKey: string,
  ): void {
    if (this.acting()) return;
    this.acting.set(version.id);
    call$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant(`PHARMACY.CATALOG_APPROVAL.${toastKey}`));
        this.acting.set(null);
        this.refusing.set(null);
        this.load();
        this.decided.emit();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY.CATALOG_APPROVAL.ACTION_FAILED'),
        );
        this.acting.set(null);
      },
    });
  }
}
