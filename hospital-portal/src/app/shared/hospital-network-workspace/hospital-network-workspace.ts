import { RangePagerComponent } from '../range-pager/range-pager.component';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { HospitalService, HospitalResponse } from '../../services/hospital.service';
import { HospitalScopeChipComponent } from '../hospital-scope-chip/hospital-scope-chip.component';

/** The global entry for facility-only modules. Never mounts their scoped APIs without a row target. */
@Component({
  selector: 'app-hospital-network-workspace',
  standalone: true,
  imports: [FormsModule, TranslateModule, HospitalScopeChipComponent, RangePagerComponent],
  templateUrl: './hospital-network-workspace.html',
  styleUrl: './hospital-network-workspace.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HospitalNetworkWorkspaceComponent implements OnInit {
  private readonly service = inject(HospitalService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly moduleTitleKey = input('HOSPITAL_NETWORK.TITLE');
  readonly hospitals = signal<HospitalResponse[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly search = signal('');
  readonly activeOnly = signal(false);
  readonly page = signal(0);
  readonly pageSize = 20;
  readonly visibleRows = computed(() =>
    this.filtered().slice(this.page() * this.pageSize, (this.page() + 1) * this.pageSize),
  );
  private requestVersion = 0;
  readonly activeCount = computed(() => this.hospitals().filter((h) => h.active).length);
  readonly filtered = computed(() => {
    const query = this.search().trim().toLocaleLowerCase();
    return this.hospitals()
      .filter(
        (h) =>
          (!this.activeOnly() || h.active) &&
          (!query ||
            [h.name, h.code, h.city, h.organizationName].some((v) =>
              v?.toLocaleLowerCase().includes(query),
            )),
      )
      .sort((a, b) => a.name.localeCompare(b.name));
  });
  ngOnInit(): void {
    this.load();
  }
  load(): void {
    const version = ++this.requestVersion;
    this.page.set(0);
    this.loading.set(true);
    this.failed.set(false);
    this.service
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (hospitals) => {
          if (version !== this.requestVersion) return;
          this.hospitals.set(hospitals);
          this.loading.set(false);
        },
        error: () => {
          if (version !== this.requestVersion) return;
          this.hospitals.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }
  reset(): void {
    this.search.set('');
    this.activeOnly.set(false);
    this.page.set(0);
  }
  openHospital(hospital: HospitalResponse): void {
    if (!this.hospitals().some((h) => h.id === hospital.id)) return;
    const target = this.router.parseUrl(this.router.url);
    target.queryParams = { ...target.queryParams, hospitalId: hospital.id };
    void this.router.navigateByUrl(target);
  }
}
