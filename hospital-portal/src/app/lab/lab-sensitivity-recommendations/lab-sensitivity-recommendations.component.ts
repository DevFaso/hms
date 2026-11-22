import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import {
  LabSensitivityRecommendation,
  SensitivePolicyService,
} from '../../services/sensitive-policy.service';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import { refusalReason } from '../../shared/refusal-reason';
import { sensitivityLabelKey } from '../../shared/sensitivity/sensitivity-category';
import { RuleStatusComponent } from '../../shared/rule-status/rule-status.component';

/**
 * Policy 2026-11-20 (provisional): « Appliquer les catégories recommandées ».
 *
 * A curated list recommends a sensitive category for some tests of the
 * catalogue (HIV serology, TPHA/VDRL, HBsAg, β-hCG, drug screens, Hb
 * electrophoresis…). Nothing is tagged silently: the lab director opens this
 * dialog, sees each match and what matched, ticks the ones to apply and
 * confirms. A test the list does not name is never shown and never tagged
 * here. The list is provisional until a clinician signs it off, and says so.
 */
@Component({
  selector: 'app-lab-sensitivity-recommendations',
  standalone: true,
  imports: [RuleStatusComponent, TranslateModule, DialogFocusDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './lab-sensitivity-recommendations.component.html',
  styleUrl: './lab-sensitivity-recommendations.component.scss',
})
export class LabSensitivityRecommendationsComponent {
  private readonly policy = inject(SensitivePolicyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** Tags were applied: the host reloads its catalogue. */
  readonly applied = output<void>();

  readonly open = signal(false);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly saving = signal(false);
  readonly rows = signal<LabSensitivityRecommendation[]>([]);
  /** The definitions the director ticked. */
  readonly chosen = signal<ReadonlySet<string>>(new Set());

  /** The matches still to apply, then the ones the catalogue already carries. */
  readonly pending = computed(() => this.rows().filter((row) => !row.applied));
  readonly done = computed(() => this.rows().filter((row) => row.applied));
  readonly chosenCount = computed(() => this.chosen().size);
  /** The button was pressed with nothing ticked: the dialog says so until a box is ticked. */
  readonly nothingTicked = signal(false);

  readonly labelOf = sensitivityLabelKey;

  show(): void {
    this.nothingTicked.set(false);
    this.open.set(true);
    this.load();
  }

  close(): void {
    if (this.saving()) return;
    this.open.set(false);
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.policy.labRecommendations().subscribe({
      next: (rows) => {
        this.rows.set(rows);
        // Ticked by default: a test with no category yet. One the director
        // classified otherwise is shown, and left for them to tick.
        this.chosen.set(
          new Set(
            rows
              .filter((row) => !row.applied && !row.currentCategory)
              .map((row) => row.definitionId),
          ),
        );
        this.loading.set(false);
      },
      error: () => {
        this.rows.set([]);
        this.chosen.set(new Set());
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  isChosen(row: LabSensitivityRecommendation): boolean {
    return this.chosen().has(row.definitionId);
  }

  toggle(row: LabSensitivityRecommendation, checked: boolean): void {
    const next = new Set(this.chosen());
    if (checked) next.add(row.definitionId);
    else next.delete(row.definitionId);
    this.chosen.set(next);
    if (next.size) this.nothingTicked.set(false);
  }

  /** The label key of the category recommended for a test. */
  recommendedKey(row: LabSensitivityRecommendation): string {
    return sensitivityLabelKey(row.recommendedCategory);
  }

  matchKey(row: LabSensitivityRecommendation): string {
    return `SENSITIVE_POLICY.RECO.MATCHED_${row.matchedOn}`;
  }

  apply(): void {
    const chosen = this.chosen();
    const items = this.pending()
      .filter((row) => chosen.has(row.definitionId))
      .map((row) => ({ definitionId: row.definitionId, category: row.recommendedCategory }));
    if (this.saving()) return;
    if (!items.length) {
      this.nothingTicked.set(true);
      return;
    }
    this.nothingTicked.set(false);
    this.saving.set(true);
    this.policy.applyLabRecommendations(items).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.chosen.set(new Set());
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('SENSITIVE_POLICY.RECO.APPLIED', { count: items.length }),
        );
        this.applied.emit();
        this.open.set(false);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('SENSITIVE_POLICY.RECO.APPLY_FAILED'),
        );
      },
    });
  }
}
