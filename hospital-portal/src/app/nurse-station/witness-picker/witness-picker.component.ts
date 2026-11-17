import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule } from '@ngx-translate/core';

import {
  AdministrationWitnessCandidate,
  NurseTaskService,
} from '../../services/nurse-task.service';

/**
 * QA D922 (decision 2026-10-28): the second clinician of the same hospital
 * who witnesses a controlled drug being given. The one picker the station MAR
 * and the eMAR both use; the server lists the active nurses, midwives and
 * doctors of the active hospital, the caller excluded, and refuses anyone
 * else in the reader's language. `scripts/lib/clinical-workflow-a11y.test.mjs`
 * fails when a component records a dose without it.
 */
@Component({
  selector: 'app-witness-picker',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="witness-picker" data-testid="witness-picker">
      <label [for]="inputId()">{{ 'NURSE.WITNESS.LABEL' | translate }} *</label>
      <select
        [id]="inputId()"
        data-testid="witness-select"
        [value]="selected() ?? ''"
        (change)="pick($any($event.target).value)"
        [attr.aria-describedby]="inputId() + '-hint'"
      >
        <option value="">{{ 'NURSE.WITNESS.CHOOSE' | translate }}</option>
        @for (c of candidates(); track c.userId) {
          <option [value]="c.userId">{{ c.displayName }}</option>
        }
      </select>
      <p class="witness-hint" [id]="inputId() + '-hint'">
        @if (loadFailed()) {
          {{ 'NURSE.WITNESS.LOAD_FAILED' | translate }}
        } @else if (loaded() && candidates().length === 0) {
          {{ 'NURSE.WITNESS.NONE' | translate }}
        } @else {
          {{ 'NURSE.WITNESS.HINT' | translate }}
        }
      </p>
    </div>
  `,
  styles: [
    `
      .witness-picker {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        margin: 0.75rem 0;
      }
      .witness-hint {
        font-size: 0.85rem;
        color: var(--text-secondary, #555);
        margin: 0;
      }
    `,
  ],
})
export class WitnessPickerComponent implements OnInit {
  readonly inputId = input('witness-select');
  readonly witnessChange = output<string | null>();

  protected readonly candidates = signal<AdministrationWitnessCandidate[]>([]);
  protected readonly selected = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly loadFailed = signal(false);

  private readonly nurseTaskService = inject(NurseTaskService);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    this.nurseTaskService
      .getAdministrationWitnessCandidates()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.candidates.set(list ?? []);
          this.loaded.set(true);
        },
        error: () => this.loadFailed.set(true),
      });
  }

  protected pick(value: string): void {
    const id = value || null;
    this.selected.set(id);
    this.witnessChange.emit(id);
  }
}
