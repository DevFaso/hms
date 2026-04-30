import {
  ChangeDetectionStrategy,
  Component,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription, takeUntil } from 'rxjs';

import {
  AllergySeverity,
  PatientStoryboard,
  StoryboardService,
} from '../../services/storyboard.service';

type LoadState = 'loading' | 'ready' | 'empty' | 'error';

const SEVERE_SEVERITIES: ReadonlySet<AllergySeverity> = new Set(['SEVERE', 'LIFE_THREATENING']);

/**
 * Persistent Epic-style Storyboard banner shown above every chart route.
 * Surfaces the four high-impact safety items — allergies, active problems,
 * the active encounter, and code status — fetched from the aggregating
 * {@code /api/patients/:id/storyboard} endpoint.
 *
 * <p>Designed to be small enough to fit on a low-RAM Android phone over
 * a slow connection: a single network call, capped chip lists, no
 * blocking on errors, and stale responses are cancelled when the
 * patient changes so the banner never shows the wrong patient.
 */
@Component({
  selector: 'app-storyboard-banner',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './storyboard-banner.component.html',
  styleUrl: './storyboard-banner.component.scss',
})
export class StoryboardBannerComponent implements OnChanges, OnDestroy {
  readonly patientId = input<string | null | undefined>(null);
  readonly hospitalId = input<string | null | undefined>(null);

  protected readonly state = signal<LoadState>('loading');
  protected readonly summary = signal<PatientStoryboard | null>(null);

  protected readonly hasAllergies = computed(() => (this.summary()?.allergies?.length ?? 0) > 0);
  protected readonly hasProblems = computed(() => (this.summary()?.problems?.length ?? 0) > 0);
  protected readonly hasEncounter = computed(() => !!this.summary()?.activeEncounter);
  protected readonly hasCodeStatus = computed(() => {
    const cs = this.summary()?.codeStatus;
    if (!cs) return false;
    return !!(cs.status || (cs.directives && cs.directives.length > 0));
  });

  private readonly storyboardService = inject(StoryboardService);
  private readonly destroyed$ = new Subject<void>();
  private inFlight?: Subscription;

  ngOnChanges(_changes: SimpleChanges): void {
    const id = this.patientId();
    if (!id) {
      this.cancelInFlight();
      this.summary.set(null);
      this.state.set('empty');
      return;
    }
    this.load(id, this.hospitalId() ?? undefined);
  }

  ngOnDestroy(): void {
    this.cancelInFlight();
    this.destroyed$.next();
    this.destroyed$.complete();
  }

  protected isHighSeverity(severity?: AllergySeverity | null): boolean {
    return !!severity && SEVERE_SEVERITIES.has(severity);
  }

  protected severityClass(severity?: AllergySeverity | null): string {
    if (!severity) return 'storyboard__chip--neutral';
    switch (severity) {
      case 'LIFE_THREATENING':
      case 'SEVERE':
        return 'storyboard__chip--danger';
      case 'MODERATE':
        return 'storyboard__chip--warning';
      case 'MILD':
        return 'storyboard__chip--info';
      default:
        return 'storyboard__chip--neutral';
    }
  }

  protected codeStatusClass(status?: string | null): string {
    if (!status) return 'storyboard__chip--neutral';
    const upper = status.toUpperCase();
    if (upper.includes('FULL')) return 'storyboard__chip--info';
    if (upper.includes('DNR') || upper.includes('DNI') || upper.includes('COMFORT')) {
      return 'storyboard__chip--danger';
    }
    return 'storyboard__chip--warning';
  }

  /**
   * Cancel the previous request before issuing a new one. Without this, a stale
   * response from a prior `patientId` could land *after* a newer one and overwrite
   * the banner state for the wrong patient — a clinical-safety hazard.
   */
  private load(patientId: string, hospitalId?: string): void {
    this.cancelInFlight();
    this.state.set('loading');
    this.inFlight = this.storyboardService
      .getStoryboard(patientId, hospitalId)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (s) => {
          this.summary.set(s ?? null);
          const empty =
            !s ||
            ((s.allergies?.length ?? 0) === 0 &&
              (s.problems?.length ?? 0) === 0 &&
              !s.activeEncounter &&
              !(s.codeStatus?.status || (s.codeStatus?.directives?.length ?? 0) > 0));
          this.state.set(empty ? 'empty' : 'ready');
        },
        error: () => {
          this.summary.set(null);
          this.state.set('error');
        },
      });
  }

  private cancelInFlight(): void {
    this.inFlight?.unsubscribe();
    this.inFlight = undefined;
  }
}
