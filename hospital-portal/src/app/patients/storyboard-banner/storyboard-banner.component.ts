import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription, takeUntil } from 'rxjs';

import {
  AllergySeverity,
  PatientStoryboard,
  StoryboardService,
} from '../../services/storyboard.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { RestrictedRowsComponent } from '../restricted-rows/restricted-rows.component';

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
  imports: [CommonModule, TranslateModule, EnumLabelPipe, RestrictedRowsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './storyboard-banner.component.html',
  styleUrl: './storyboard-banner.component.scss',
})
export class StoryboardBannerComponent implements OnChanges, AfterViewInit, OnDestroy {
  readonly patientId = input<string | null | undefined>(null);
  readonly hospitalId = input<string | null | undefined>(null);
  /**
   * E9 #64 — bumped by the host when a break-the-glass session is declared
   * or ends. Any change re-reads the storyboard: what it withholds depends
   * on the session.
   */
  readonly refreshToken = input<number>(0);

  /** E9 #64 — "Ouvrir avec motif" on a restricted line; the host opens the declaration. */
  readonly openRestricted = output<void>();

  /**
   * True once the banner is pinned rather than sitting in its place at the
   * top of the chart. The full card block is ~230px tall, so leaving it
   * expanded while pinned costs a quarter of the viewport on every chart and
   * makes the rest of the page look like it is sliding under a bridge.
   * Condensed, it keeps the two things that must never scroll away — who the
   * patient is, and the allergy / code-status flags.
   */
  protected readonly condensed = signal(false);

  private readonly sentinel = viewChild<ElementRef<HTMLElement>>('stickySentinel');
  private readonly host = inject(ElementRef<HTMLElement>);
  private stickyObserver?: IntersectionObserver;

  protected readonly state = signal<LoadState>('loading');
  protected readonly summary = signal<PatientStoryboard | null>(null);

  protected readonly hasAllergies = computed(() => (this.summary()?.allergies?.length ?? 0) > 0);
  protected readonly hasProblems = computed(() => (this.summary()?.problems?.length ?? 0) > 0);
  protected readonly hasEncounter = computed(() => !!this.summary()?.activeEncounter);
  /**
   * Directives still in force. A REVOKED or EXPIRED directive rendered
   * identically to an ACTIVE one turns the code-status banner into a
   * clinical-safety hazard — a revoked DNR must not read as a standing DNR.
   */
  protected readonly activeDirectives = computed(() =>
    (this.summary()?.codeStatus?.directives ?? []).filter(
      (d) => d.status !== 'REVOKED' && d.status !== 'EXPIRED',
    ),
  );

  protected readonly hasCodeStatus = computed(() => {
    const cs = this.summary()?.codeStatus;
    if (!cs) return false;
    return !!(cs.status || this.activeDirectives().length > 0);
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

  ngAfterViewInit(): void {
    const sentinel = this.sentinel()?.nativeElement;
    // Guarded: jsdom/Karma and any SSR pass have no IntersectionObserver, and
    // the banner must still render there — it just never condenses.
    if (!sentinel || typeof IntersectionObserver === 'undefined') return;

    // Observe against the scrolling container, not the viewport. `.content`
    // sits below a fixed-height topbar, so a viewport-rooted observer would
    // only fire once the sentinel had scrolled behind the topbar — well after
    // the banner had already stuck.
    const root = this.host.nativeElement.closest('.content');
    this.stickyObserver = new IntersectionObserver(
      ([entry]) => this.condensed.set(!entry.isIntersecting),
      { root, threshold: 0 },
    );
    this.stickyObserver.observe(sentinel);
  }

  ngOnDestroy(): void {
    this.stickyObserver?.disconnect();
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

  protected codeStatusPillClass(status?: string | null): string {
    if (!status) return 'storyboard__pill--neutral';
    const upper = status.toUpperCase();
    if (upper.includes('FULL')) return 'storyboard__pill--info';
    if (upper.includes('DNR') || upper.includes('DNI') || upper.includes('COMFORT')) {
      return 'storyboard__pill--danger';
    }
    return 'storyboard__pill--warning';
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
              (s.restrictedRows?.length ?? 0) === 0 &&
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
