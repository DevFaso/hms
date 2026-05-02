import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';
import { DoctorWorklistItem } from '../../services/dashboard.service';

type WorklistTab = 'ALL' | 'WAITING' | 'IN_PROGRESS' | 'CONSULTS' | 'COMPLETED';

@Component({
  selector: 'app-doctor-worklist',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslateModule],
  templateUrl: './doctor-worklist.html',
  styleUrl: './doctor-worklist.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorWorklistComponent implements OnInit, OnDestroy {
  private readonly translate = inject(TranslateService);

  // Bumped on every language change so computed labels re-evaluate.
  private readonly langTick = signal(0);
  private langSub?: Subscription;

  items = input<DoctorWorklistItem[]>([]);
  patientSelected = output<string>();
  encounterStarted = output<string>();
  dateChanged = output<string>();

  readonly today = (() => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  })();

  activeTab = signal<WorklistTab>('ALL');
  urgencyFilter = signal<string>('');
  locationFilter = signal<string>('');
  dateFilter = signal<string>(this.today);

  uniqueLocations = computed(() => [
    ...new Set(
      this.items()
        .map((i) => i.location)
        .filter((l): l is string => !!l),
    ),
  ]);
  hasActiveFilters = computed(
    () => !!(this.urgencyFilter() || this.locationFilter() || this.dateFilter() !== this.today),
  );

  readonly tabs = computed<{ key: WorklistTab; label: string }[]>(() => {
    this.langTick();
    return [
      { key: 'ALL', label: this.translate.instant('DASHBOARD.WL_TAB.ALL') },
      { key: 'WAITING', label: this.translate.instant('DASHBOARD.WL_TAB.WAITING') },
      { key: 'IN_PROGRESS', label: this.translate.instant('DASHBOARD.WL_TAB.IN_PROGRESS') },
      { key: 'CONSULTS', label: this.translate.instant('DASHBOARD.WL_TAB.CONSULTS') },
      { key: 'COMPLETED', label: this.translate.instant('DASHBOARD.WL_TAB.COMPLETED') },
    ];
  });

  filteredItems = computed(() => {
    const tab = this.activeTab();
    const urgency = this.urgencyFilter();
    const location = this.locationFilter();
    let items = this.items();
    if (tab === 'WAITING')
      items = items.filter(
        (i) =>
          i.encounterStatus === 'CHECKED_IN' ||
          i.encounterStatus === 'TRIAGE' ||
          i.encounterStatus === 'WAITING' ||
          i.encounterStatus === 'SCHEDULED',
      );
    else if (tab === 'IN_PROGRESS')
      items = items.filter((i) => i.encounterStatus === 'IN_PROGRESS');
    else if (tab === 'CONSULTS') items = items.filter((i) => i.encounterStatus === 'CONSULTATION');
    else if (tab === 'COMPLETED') items = items.filter((i) => i.encounterStatus === 'COMPLETED');
    if (urgency) items = items.filter((i) => i.urgency === urgency);
    if (location) items = items.filter((i) => i.location === location);
    return items;
  });

  tabCounts = computed(() => {
    const all = this.items();
    return {
      ALL: all.length,
      WAITING: all.filter(
        (i) =>
          i.encounterStatus === 'CHECKED_IN' ||
          i.encounterStatus === 'TRIAGE' ||
          i.encounterStatus === 'WAITING' ||
          i.encounterStatus === 'SCHEDULED',
      ).length,
      IN_PROGRESS: all.filter((i) => i.encounterStatus === 'IN_PROGRESS').length,
      CONSULTS: all.filter((i) => i.encounterStatus === 'CONSULTATION').length,
      COMPLETED: all.filter((i) => i.encounterStatus === 'COMPLETED').length,
    };
  });

  setTab(tab: WorklistTab): void {
    this.activeTab.set(tab);
  }

  setUrgencyFilter(urgency: string): void {
    this.urgencyFilter.set(urgency);
  }

  setLocationFilter(location: string): void {
    this.locationFilter.set(location);
  }

  clearFilters(): void {
    this.urgencyFilter.set('');
    this.locationFilter.set('');
    this.dateFilter.set(this.today);
    this.dateChanged.emit(this.today);
  }

  setDateFilter(date: string): void {
    this.dateFilter.set(date);
    this.dateChanged.emit(date);
  }

  selectPatient(patientId: string): void {
    this.patientSelected.emit(patientId);
  }

  getUrgencyClass(urgency: string): string {
    const map: Record<string, string> = {
      EMERGENT: 'urgency-emergent',
      URGENT: 'urgency-urgent',
      ROUTINE: 'urgency-routine',
      LOW: 'urgency-low',
    };
    return map[urgency] ?? 'urgency-routine';
  }

  // Read in templates via the langTick-driven tabs() computed; the underlying
  // ngx-translate `instant()` returns the localized value for the current lang.
  private static readonly STATUS_KEYS: Record<string, string> = {
    SCHEDULED: 'DASHBOARD.WL_STATUS.SCHEDULED',
    CHECKED_IN: 'DASHBOARD.WL_STATUS.CHECKED_IN',
    TRIAGE: 'DASHBOARD.WL_STATUS.TRIAGE',
    WAITING: 'DASHBOARD.WL_STATUS.WAITING',
    IN_PROGRESS: 'DASHBOARD.WL_STATUS.IN_PROGRESS',
    COMPLETED: 'DASHBOARD.WL_STATUS.COMPLETED',
    CANCELLED: 'DASHBOARD.WL_STATUS.CANCELLED',
    CONSULTATION: 'DASHBOARD.WL_STATUS.CONSULTATION',
  };

  getStatusLabel(status: string): string {
    this.langTick();
    const key = DoctorWorklistComponent.STATUS_KEYS[status];
    return key ? this.translate.instant(key) : status;
  }

  getStatusClass(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'status-scheduled',
      CHECKED_IN: 'status-checked-in',
      TRIAGE: 'status-triage',
      WAITING: 'status-waiting',
      IN_PROGRESS: 'status-active',
      COMPLETED: 'status-completed',
      CANCELLED: 'status-cancelled',
      CONSULTATION: 'status-consult',
    };
    return map[status] ?? '';
  }

  requestStartEncounter(encounterId: string): void {
    this.encounterStarted.emit(encounterId);
  }

  getInitials(name: string): string {
    const parts = (name ?? '').trim().split(' ');
    if (parts.length >= 2) return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
    return (parts[0]?.[0] ?? '?').toUpperCase();
  }

  getAvatarColor(name: string): string {
    const colors = ['#2563eb', '#059669', '#7c3aed', '#d97706', '#dc2626', '#0891b2', '#db2777'];
    let hash = 0;
    for (let i = 0; i < (name?.length ?? 0); i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
    return colors[Math.abs(hash) % colors.length];
  }

  ngOnInit(): void {
    this.langSub = this.translate.onLangChange.subscribe(() => {
      this.langTick.update((v) => v + 1);
    });
  }

  ngOnDestroy(): void {
    this.langSub?.unsubscribe();
  }
}
