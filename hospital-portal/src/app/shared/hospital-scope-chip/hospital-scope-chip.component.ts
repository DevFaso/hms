import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  OnInit,
  Output,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { ActivatedRoute } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { HospitalScopeUrlService } from '../../core/hospital-scope-url.service';
import { RoleContextService } from '../../core/role-context.service';
import { HospitalResponse, HospitalService } from '../../services/hospital.service';
import { HospitalTypeaheadComponent } from '../hospital-typeahead/hospital-typeahead.component';

/**
 * Header chip used at the top of every cross-tenant clinical list page
 * (consultations, encounters, admissions, prescriptions, …) to surface
 * the super-admin's current scope and let them switch via a typeahead.
 *
 * See docs/super-admin-cross-tenant-design.md for the full UX spec.
 *
 * Inputs:
 *   - none — reads `RoleContextService.globalView()` and
 *     `RoleContextService.selectedHospitalId()` directly so every page
 *     stays in sync without each page having to wire @Input bindings.
 *
 * Outputs:
 *   - `scopeChange` — fires after the chip mutates `RoleContextService`
 *     so host pages can re-fetch their list. Payload is the new
 *     selected hospital id, or `null` for global view.
 *
 * Visibility:
 *   - rendered as `display: none` for non-super-admins so list pages
 *     can drop the chip into their header unconditionally.
 */
@Component({
  selector: 'app-hospital-scope-chip',
  standalone: true,
  imports: [TranslateModule, HospitalTypeaheadComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hospital-scope-chip.component.html',
  styleUrl: './hospital-scope-chip.component.scss',
})
export class HospitalScopeChipComponent implements OnInit {
  private readonly roleContext = inject(RoleContextService);
  private readonly hostElement = inject(ElementRef<HTMLElement>);
  private readonly route = inject(ActivatedRoute);
  private readonly hospitalService = inject(HospitalService);
  private readonly scopeUrl = inject(HospitalScopeUrlService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Emits AFTER `RoleContextService` and the URL query-param have been
   * updated. Host pages should re-fetch their list in response. Payload
   * is the new selected hospital id, or `null` for global view.
   */
  @Output() readonly scopeChange = new EventEmitter<string | null>();

  /**
   * When `true`, the chip skips URL synchronisation. Use this on pages
   * that have their own router-aware filter encoding and don't want a
   * `?hospitalId=` query-param appearing alongside it. Default `false`
   * (the design-doc'd behaviour: every list page round-trips scope via
   * the URL so back/forward/share-link work).
   *
   * Bound as an {@link Input} so callers can opt out from the template:
   * {@code <app-hospital-scope-chip [disableUrlSync]="true" />}.
   */
  @Input() disableUrlSync = false;

  /**
   * When `true` the chip neither reads `?hospitalId=` on init nor resets the
   * scope to global view: it shows whatever the session already pinned. The
   * shell's route-level gate (HospitalScopeGateService) uses it so a
   * super-admin picks a hospital once and keeps it across every gated page;
   * the gate applies a URL scope itself on navigation. Page-level chips keep
   * the default, where the URL is the state
   * (docs/super-admin-cross-tenant-design.md).
   */
  @Input() preserveScope = false;
  /** Facility-only pages cannot offer a global read or clear to an unusable global state. */
  @Input() hospitalRequired = false;

  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;
  protected readonly globalView = this.roleContext.globalView;
  protected readonly selectedHospitalId = this.roleContext.selectedHospitalId;

  /** Cached display name for the currently scoped hospital (null in global view). */
  private readonly _selectedHospitalName = signal<string | null>(null);
  protected readonly selectedHospitalName = computed(() => this._selectedHospitalName());
  private namedHospitalId: string | null = null;

  constructor() {
    effect((onCleanup) => {
      // The shell keeps this chip mounted while URL drill-downs replace its outlet.
      // Page-owned chips continue to initialise/synchronise their own URL scope.
      if (!this.preserveScope || !this.isSuperAdmin()) return;
      const id = this.selectedHospitalId();
      if (this.globalView() || !id) {
        this.namedHospitalId = null;
        this._selectedHospitalName.set(null);
        return;
      }
      if (this.namedHospitalId === id) return;
      this._selectedHospitalName.set(null);
      const subscription = this.hospitalService.getById(id).subscribe({
        next: (hospital) => {
          if (this.selectedHospitalId() !== id) return;
          this.namedHospitalId = id;
          this._selectedHospitalName.set(hospital.name);
        },
        error: () => {
          if (this.selectedHospitalId() === id) this._selectedHospitalName.set(null);
        },
      });
      onCleanup(() => subscription.unsubscribe());
    });
  }

  protected readonly overlayOpen = signal<boolean>(false);

  /**
   * Allow host pages to seed the chip with the hospital name when the
   * page boots from a `?hospitalId=…` URL (so the chip doesn't render
   * "Hospital ✕" without a label while it waits for a name lookup).
   *
   * In typical use the chip handles this itself in `ngOnInit` via
   * `HospitalService.getById(...)`; the setter remains for tests and
   * any host that wants to pre-populate the label without an extra
   * round trip.
   */
  setSelectedHospitalName(name: string | null): void {
    this.namedHospitalId = name ? this.selectedHospitalId() : null;
    this._selectedHospitalName.set(name);
  }

  ngOnInit(): void {
    if (!this.roleContext.isSuperAdmin()) {
      return;
    }
    // Idempotent re-apply: the host page's `ngOnInit` may have already
    // called `HospitalScopeUrlService.applyUrlScopeSync()` to ensure
    // the X-Hospital-Id header is correct on the first list fetch.
    // Calling it again here lets the chip work even on pages that
    // forgot the pre-load step (degraded UX — first load races, then
    // self-corrects on the chip's emission).
    if (this.preserveScope) return;
    const scopedId = this.scopeUrl.applyUrlScopeSync(this.route);
    if (scopedId) {
      this.hospitalService
        .getById(scopedId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (hospital) => this._selectedHospitalName.set(hospital.name),
          error: () => this._selectedHospitalName.set(null),
        });
    }
  }

  private syncUrl(hospitalId: string | null): void {
    if (this.disableUrlSync) {
      return;
    }
    this.scopeUrl.syncScopeToUrl(this.route, hospitalId);
  }

  protected toggleOverlay(): void {
    this.overlayOpen.update((open) => !open);
  }

  protected closeOverlay(): void {
    this.overlayOpen.set(false);
  }

  protected onSelectAll(): void {
    if (this.hospitalRequired) return;
    const changed = !this.roleContext.globalView();
    this.roleContext.enableGlobalView();
    this._selectedHospitalName.set(null);
    this.overlayOpen.set(false);
    if (changed) {
      this.syncUrl(null);
      this.scopeChange.emit(null);
    }
  }

  protected onSelectHospital(hospital: HospitalResponse): void {
    // Hosts key their loads on the effective id, so re-picking the hospital
    // they already use (the pinned one, or the primary in scoped view) is a
    // no-op for them: the chip updates its label and closes, nothing else.
    const changed =
      this.roleContext.globalView() ||
      this.roleContext.effectiveHospitalIdForRequest() !== hospital.id;
    this.roleContext.scopeToHospital(hospital.id);
    this.namedHospitalId = hospital.id;
    this._selectedHospitalName.set(hospital.name);
    this.overlayOpen.set(false);
    if (changed) {
      this.syncUrl(hospital.id);
      this.scopeChange.emit(hospital.id);
    }
  }

  /**
   * Click target on the chip's "✕" — clears the scope back to global
   * view without re-opening the overlay (matches the design doc).
   */
  protected clearScope(event: Event): void {
    event.stopPropagation();
    this.onSelectAll();
  }

  /** Close the overlay when the user clicks anywhere outside the chip. */
  @HostListener('document:click', ['$event.target'])
  protected onDocumentClick(target: EventTarget | null): void {
    if (!this.overlayOpen()) {
      return;
    }
    if (target instanceof Node && this.hostElement.nativeElement.contains(target)) {
      return;
    }
    this.overlayOpen.set(false);
  }
}
