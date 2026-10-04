import { Subscription } from 'rxjs';
import {
  Component,
  inject,
  OnInit,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { OrganizationService, OrganizationResponse } from '../services/organization.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import {
  PlatformService,
  PlatformSummary,
  CatalogItem,
  OrgServiceResponse,
  OrgServiceUpdateRequest,
  OrgServiceRegisterRequest,
  HospitalServiceLink,
  ReleaseWindowRequest,
  ReleaseWindowResponse,
  PlatformServiceStatus,
  PlatformServiceType,
  AutomationTask,
} from '../services/platform.service';

/* ── View state types ── */

type ActiveView = 'dashboard' | 'services' | 'catalog' | 'releases';

interface ServiceForm {
  serviceType: PlatformServiceType;
  provider: string;
  baseUrl: string;
  documentationUrl: string;
  apiKeyReference: string;
  /** Edit only: remove the stored API-key reference (D5). */
  clearApiKey: boolean;
  managedByPlatform: boolean;
  ownerTeam: string;
  ownerContactEmail: string;
  dataSteward: string;
  serviceLevel: string;
  integrationNotes: string;
}

interface ReleaseForm {
  name: string;
  description: string;
  environment: string;
  startsAt: string;
  endsAt: string;
  freezeChanges: boolean;
  ownerTeam: string;
  notes: string;
}

/** One hospital of the service's organization and its link, if any (D2/D3). */
export interface HospitalLinkRow {
  hospitalId: string;
  hospitalName: string;
  link: HospitalServiceLink | null;
}

/** The navigation a dashboard task offers — it never claims to run anything (D8). */
export interface TaskAction {
  labelKey: string;
  icon: string;
  view: ActiveView;
}

const EMPTY_SERVICE_FORM: ServiceForm = {
  serviceType: 'EHR',
  provider: '',
  baseUrl: '',
  documentationUrl: '',
  apiKeyReference: '',
  clearApiKey: false,
  managedByPlatform: false,
  ownerTeam: '',
  ownerContactEmail: '',
  dataSteward: '',
  serviceLevel: '',
  integrationNotes: '',
};

const EMPTY_RELEASE_FORM: ReleaseForm = {
  name: '',
  description: '',
  environment: 'staging',
  startsAt: '',
  endsAt: '',
  freezeChanges: false,
  ownerTeam: '',
  notes: '',
};

const SERVICE_TYPES: PlatformServiceType[] = [
  'EHR',
  'BILLING',
  'INVENTORY',
  'LIMS',
  'ANALYTICS',
  'CLINICAL_ANALYTICS',
  'REMOTE_MONITORING',
  'PEDIATRIC_MESSAGING',
  'ORTHO_IMAGING',
  'RESP_TELEMED',
];

const STATUS_OPTIONS: PlatformServiceStatus[] = [
  'ACTIVE',
  'PILOT',
  'INACTIVE',
  'PENDING',
  'DECOMMISSIONED',
];

/** Organizations offered in the org pickers (services toolbar, provision target). */
const ORG_PAGE_SIZE = 50;

/**
 * Hosts that only ever appear in seeded or example data. A catalog entry
 * pointing at one has no working page behind it, so the page shows the
 * address as text instead of a link or a "Docs" / "Sandbox" button (D12).
 */
const PLACEHOLDER_HOSTS: RegExp[] = [
  /\.local$/i,
  /\.internal$/i,
  /^localhost$/i,
  /(^|\.)example\.(com|org|net)$/i,
];

/** API field paths the platform forms send, mapped to the label the user saw (D11). */
const FIELD_LABEL_KEYS: Record<string, string> = {
  provider: 'PLATFORM.PROVIDER',
  baseUrl: 'PLATFORM.BASE_URL',
  documentationUrl: 'PLATFORM.DOCUMENTATION_URL',
  apiKeyReference: 'PLATFORM.API_KEY_REF',
  'ownership.ownerTeam': 'PLATFORM.OWNER_TEAM',
  'ownership.ownerContactEmail': 'PLATFORM.CONTACT_EMAIL',
  'ownership.dataSteward': 'PLATFORM.DATA_STEWARD',
  'ownership.serviceLevel': 'PLATFORM.SERVICE_LEVEL',
  'metadata.integrationNotes': 'PLATFORM.INTEGRATION_NOTES',
  name: 'COMMON.NAME',
  description: 'COMMON.DESCRIPTION',
  environment: 'PLATFORM.ENVIRONMENT',
  startsAt: 'PLATFORM.STARTS_AT',
  endsAt: 'PLATFORM.ENDS_AT',
  ownerTeam: 'PLATFORM.OWNER_TEAM',
  notes: 'PLATFORM.NOTES',
};

interface ApiErrorBody {
  message?: unknown;
  fieldErrors?: Record<string, string> | null;
}

function linkKey(serviceId: string, hospitalId: string): string {
  return serviceId + ':' + hospitalId;
}

@Component({
  selector: 'app-platform',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './platform.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './platform.scss',
})
export class PlatformComponent implements OnInit {
  private readonly platformSvc = inject(PlatformService);
  private readonly orgSvc = inject(OrganizationService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── Reactive state ── */
  activeView = signal<ActiveView>('dashboard');
  loading = signal(true);
  error = signal<string | null>(null);
  saving = signal(false);

  /* Dashboard */
  summary = signal<PlatformSummary | null>(null);

  /* Services */
  organizations = signal<OrganizationResponse[]>([]);
  organizationsLoaded = signal(false);
  selectedOrgId = signal<string | null>(null);
  orgServices = signal<OrgServiceResponse[]>([]);
  servicesLoading = signal(false);
  statusFilter = signal<PlatformServiceStatus | ''>('');

  /* Service detail / edit */
  selectedService = signal<OrgServiceResponse | null>(null);
  serviceDrawerOpen = signal(false);
  editingService = signal(false);
  serviceForm = signal<ServiceForm>({ ...EMPTY_SERVICE_FORM });

  /* Register new service */
  registerDrawerOpen = signal(false);
  registerForm = signal<ServiceForm>({ ...EMPTY_SERVICE_FORM });

  /* Hospital links */
  hospitalLinks = signal<HospitalServiceLink[]>([]);
  linksLoading = signal(false);
  linksError = signal(false);
  /** Hospital whose link is being changed, so only its buttons wait. */
  /**
   * Link requests in flight, keyed by service and hospital: one per row, so
   * one answer never re-enables another row's button while its own request
   * is still running.
   */
  private readonly linkBusyKeys = signal<ReadonlySet<string>>(new Set());

  isLinkBusy(hospitalId: string): boolean {
    const svc = this.selectedService();
    return !!svc && this.linkBusyKeys().has(linkKey(svc.id, hospitalId));
  }

  private setLinkBusy(serviceId: string, hospitalId: string, busy: boolean): void {
    const key = linkKey(serviceId, hospitalId);
    this.linkBusyKeys.update((keys) => {
      const next = new Set(keys);
      if (busy) next.add(key);
      else next.delete(key);
      return next;
    });
  }

  /** True while the drawer still shows the service a request was sent for. */
  private drawerShows(serviceId: string): boolean {
    return this.selectedService()?.id === serviceId;
  }

  /* Catalog */
  catalog = signal<CatalogItem[]>([]);
  catalogSearchTerm = signal('');
  selectedCatalogItem = signal<CatalogItem | null>(null);
  catalogDetailOpen = signal(false);
  /** D1: the organization to provision to — always an explicit choice, never a default. */
  provisionOrgId = signal<string | null>(null);

  /* Releases */
  releases = signal<ReleaseWindowResponse[]>([]);
  releasesLoading = signal(false);
  releasesError = signal(false);
  releaseFormOpen = signal(false);
  releaseForm = signal<ReleaseForm>({ ...EMPTY_RELEASE_FORM });

  /* Computed */
  filteredServices = computed(() => {
    const filter = this.statusFilter();
    const services = this.orgServices();
    if (!filter) return services;
    return services.filter((s) => s.status === filter);
  });

  filteredCatalog = computed(() => {
    const term = this.catalogSearchTerm().toLowerCase();
    const items = this.catalog();
    if (!term) return items;
    return items.filter(
      (i) =>
        i.displayName.toLowerCase().includes(term) ||
        i.serviceType.toLowerCase().includes(term) ||
        (i.provider && i.provider.toLowerCase().includes(term)),
    );
  });

  /**
   * D3: every hospital of the service's organization, with its link when it
   * has one — linked hospitals are no longer limited to the first hospital.
   */
  hospitalRows = computed<HospitalLinkRow[]>(() => {
    const svc = this.selectedService();
    if (!svc) return [];
    const org = this.organizations().find((o) => o.id === svc.organizationId);
    const byHospital = new Map(this.hospitalLinks().map((l) => [l.hospitalId, l]));
    const rows: HospitalLinkRow[] = (org?.hospitals ?? []).map((h) => ({
      hospitalId: h.id,
      hospitalName: h.name,
      link: byHospital.get(h.id) ?? null,
    }));
    for (const link of this.hospitalLinks()) {
      if (!rows.some((r) => r.hospitalId === link.hospitalId)) {
        rows.push({ hospitalId: link.hospitalId, hospitalName: link.hospitalName, link });
      }
    }
    return rows.sort((a, b) => (a.hospitalName ?? '').localeCompare(b.hospitalName ?? ''));
  });

  readonly serviceTypes = SERVICE_TYPES;
  readonly statusOptions = STATUS_OPTIONS;

  ngOnInit(): void {
    this.loadDashboard();
  }

  /* ══════════════════════════════════════════
     Navigation
     ══════════════════════════════════════════ */

  switchView(view: ActiveView): void {
    this.activeView.set(view);
    switch (view) {
      case 'dashboard':
        this.loadDashboard();
        break;
      case 'services':
        this.loadOrganizations();
        break;
      case 'catalog':
        this.loadCatalog();
        break;
      case 'releases':
        this.loadDashboard();
        this.loadReleases();
        break;
    }
  }

  /* ══════════════════════════════════════════
     Dashboard
     ══════════════════════════════════════════ */

  loadDashboard(): void {
    this.loading.set(true);
    this.error.set(null);

    this.platformSvc.getSummary().subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.loading.set(false);
      },
      error: () => {
        this.summary.set(null);
        this.loading.set(false);
        this.error.set(this.translate.instant('PLATFORM.SUMMARY_LOAD_FAILED'));
      },
    });
  }

  exportSnapshot(): void {
    this.saving.set(true);
    this.platformSvc.getSnapshot().subscribe({
      next: (snap) => {
        const blob = new Blob([JSON.stringify(snap, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `platform-snapshot-${new Date().toISOString().slice(0, 10)}.json`;
        a.click();
        URL.revokeObjectURL(url);
        this.toast.success(this.translate.instant('PLATFORM.TOAST.SNAPSHOT_EXPORTED'));
        this.saving.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PLATFORM.TOAST.SNAPSHOT_EXPORT_FAILED'));
        this.saving.set(false);
      },
    });
  }

  /* ══════════════════════════════════════════
     Organizations
     ══════════════════════════════════════════ */

  /**
   * D9: entering the Services tab always (re)loads the services of the
   * selected organization — the list used to stay stale (or empty) when an
   * organization had already been selected elsewhere.
   */
  loadOrganizations(): void {
    this.servicesLoading.set(true);
    this.orgSvc.list(0, ORG_PAGE_SIZE).subscribe({
      next: (page) => {
        this.organizations.set(page.content);
        this.organizationsLoaded.set(true);
        this.servicesLoading.set(false);
        const current = this.selectedOrgId();
        if (current && page.content.some((o) => o.id === current)) {
          this.loadOrgServices(current);
        } else if (page.content.length > 0) {
          this.selectOrg(page.content[0].id);
        } else {
          this.selectedOrgId.set(null);
          this.orgServices.set([]);
        }
      },
      error: () => {
        this.toast.error(this.translate.instant('PLATFORM.TOAST.ORGS_LOAD_FAILED'));
        this.servicesLoading.set(false);
      },
    });
  }

  /** The provision picker needs the organizations; it never selects one (D1). */
  private ensureOrganizations(): void {
    if (this.organizationsLoaded()) return;
    this.orgSvc.list(0, ORG_PAGE_SIZE).subscribe({
      next: (page) => {
        this.organizations.set(page.content);
        this.organizationsLoaded.set(true);
      },
      error: () => this.toast.error(this.translate.instant('PLATFORM.TOAST.ORGS_LOAD_FAILED')),
    });
  }

  selectOrg(orgId: string): void {
    this.selectedOrgId.set(orgId);
    this.loadOrgServices(orgId);
  }

  loadOrgServices(orgId: string): void {
    this.servicesLoading.set(true);
    this.platformSvc.listOrgServices(orgId).subscribe({
      next: (services) => {
        this.orgServices.set(services);
        this.servicesLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PLATFORM.TOAST.ORG_SERVICES_LOAD_FAILED'));
        this.servicesLoading.set(false);
      },
    });
  }

  /* ══════════════════════════════════════════
     Organization Services
     ══════════════════════════════════════════ */

  openServiceDetail(service: OrgServiceResponse): void {
    this.selectedService.set(service);
    this.serviceDrawerOpen.set(true);
    this.editingService.set(false);
    this.loadHospitalLinksForService(service);
  }

  closeServiceDrawer(): void {
    this.linksSub?.unsubscribe();
    this.linksLoading.set(false);
    this.serviceDrawerOpen.set(false);
    this.selectedService.set(null);
    this.editingService.set(false);
  }

  private linksSub?: Subscription;

  /** D3: one call for every hospital's link to this service. */
  loadHospitalLinksForService(service: OrgServiceResponse): void {
    // One drawer, one request: a slower answer for the service opened before
    // must never fill this one, or Disable/Unlink would act on links it shows
    // but this service does not have.
    this.linksSub?.unsubscribe();
    this.linksLoading.set(true);
    this.linksError.set(false);
    this.hospitalLinks.set([]);
    this.linksSub = this.platformSvc
      .listServiceHospitalLinks(service.organizationId, service.id)
      .subscribe({
        next: (links) => {
          this.hospitalLinks.set(links);
          this.linksLoading.set(false);
        },
        error: (err) => {
          this.linksLoading.set(false);
          this.linksError.set(true);
          this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.HOSPITAL_LINKS_LOAD_FAILED'));
        },
      });
  }

  startEditService(): void {
    const svc = this.selectedService();
    if (!svc) return;
    this.serviceForm.set({
      serviceType: svc.serviceType,
      provider: svc.provider ?? '',
      baseUrl: svc.baseUrl ?? '',
      documentationUrl: svc.documentationUrl ?? '',
      // Write-only since item 45 - the server no longer returns the
      // value. Blank means keep; typing replaces; the checkbox clears.
      apiKeyReference: '',
      clearApiKey: false,
      managedByPlatform: svc.managedByPlatform,
      ownerTeam: svc.ownership?.ownerTeam ?? '',
      ownerContactEmail: svc.ownership?.ownerContactEmail ?? '',
      dataSteward: svc.ownership?.dataSteward ?? '',
      serviceLevel: svc.ownership?.serviceLevel ?? '',
      integrationNotes: svc.metadata?.integrationNotes ?? '',
    });
    this.editingService.set(true);
  }

  cancelEditService(): void {
    this.editingService.set(false);
  }

  /**
   * D5: every edited text field is sent as typed (trimmed), so an emptied
   * field arrives as `''` and the server clears it — `undefined` meant
   * "unchanged" and a field could never be cleared. Metadata names only
   * the notes this form edits; the server merges field by field (D4).
   */
  buildUpdateRequest(f: ServiceForm): OrgServiceUpdateRequest {
    const request: OrgServiceUpdateRequest = {
      provider: f.provider.trim(),
      baseUrl: f.baseUrl.trim(),
      documentationUrl: f.documentationUrl.trim(),
      managedByPlatform: f.managedByPlatform,
      ownership: {
        ownerTeam: f.ownerTeam.trim(),
        ownerContactEmail: f.ownerContactEmail.trim(),
        dataSteward: f.dataSteward.trim(),
        serviceLevel: f.serviceLevel.trim(),
      },
      metadata: {
        integrationNotes: f.integrationNotes.trim(),
      },
    };
    if (f.clearApiKey) {
      request.clearApiKeyReference = true;
    } else if (f.apiKeyReference.trim()) {
      request.apiKeyReference = f.apiKeyReference.trim();
    }
    return request;
  }

  saveService(): void {
    const svc = this.selectedService();
    if (!svc) return;

    const request = this.buildUpdateRequest(this.serviceForm());

    this.saving.set(true);
    this.platformSvc.updateOrgService(svc.organizationId, svc.id, request).subscribe({
      next: (updated) => {
        this.orgServices.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
        // The drawer may show another service by now; never switch it back.
        if (this.drawerShows(updated.id)) {
          this.selectedService.set(updated);
          this.editingService.set(false);
        }
        this.saving.set(false);
        this.toast.success(this.translate.instant('PLATFORM.TOAST.SERVICE_UPDATED'));
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.SERVICE_UPDATE_FAILED'));
      },
    });
  }

  changeServiceStatus(service: OrgServiceResponse, newStatus: PlatformServiceStatus): void {
    this.saving.set(true);
    this.platformSvc
      .updateOrgService(service.organizationId, service.id, { status: newStatus })
      .subscribe({
        next: (updated) => {
          this.orgServices.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
          if (this.selectedService()?.id === updated.id) {
            this.selectedService.set(updated);
          }
          this.saving.set(false);
          this.toast.success(
            this.translate.instant('PLATFORM.TOAST.STATUS_CHANGED', {
              // The badge on this page renders the same value through enumLabel;
              // interpolating the raw token here produced "modifié en DECOMMISSIONED".
              status: this.translate.instant('PORTAL.ENUM.PLATFORM_SERVICE_STATUS.' + newStatus),
            }),
          );
        },
        error: (err) => {
          this.saving.set(false);
          this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.STATUS_CHANGE_FAILED'));
        },
      });
  }

  openRegisterDrawer(): void {
    this.registerForm.set({ ...EMPTY_SERVICE_FORM });
    this.registerDrawerOpen.set(true);
  }

  closeRegisterDrawer(): void {
    this.registerDrawerOpen.set(false);
  }

  registerService(): void {
    const orgId = this.selectedOrgId();
    if (!orgId) return;

    const f = this.registerForm();
    const request: OrgServiceRegisterRequest = {
      serviceType: f.serviceType,
      provider: f.provider || undefined,
      baseUrl: f.baseUrl || undefined,
      documentationUrl: f.documentationUrl || undefined,
      apiKeyReference: f.apiKeyReference || undefined,
      managedByPlatform: f.managedByPlatform,
      ownership: {
        ownerTeam: f.ownerTeam || undefined,
        ownerContactEmail: f.ownerContactEmail || undefined,
        dataSteward: f.dataSteward || undefined,
        serviceLevel: f.serviceLevel || undefined,
      },
      metadata: {
        integrationNotes: f.integrationNotes || undefined,
      },
    };

    this.saving.set(true);
    this.platformSvc.registerOrgService(orgId, request).subscribe({
      next: (created) => {
        this.orgServices.update((list) => [...list, created]);
        this.registerDrawerOpen.set(false);
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.SERVICE_REGISTERED', {
            name: this.serviceTypeLabel(f.serviceType),
          }),
        );
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.SERVICE_REGISTER_FAILED'));
      },
    });
  }

  /* ══════════════════════════════════════════
     Hospital links (D2/D3)
     ══════════════════════════════════════════ */

  /** Creates the link for a hospital that has none. */
  linkHospital(row: HospitalLinkRow): void {
    const svc = this.selectedService();
    if (!svc || row.link) return;
    this.setLinkBusy(svc.id, row.hospitalId, true);
    this.platformSvc.linkHospital(row.hospitalId, svc.id, { enabled: true }).subscribe({
      next: (created) => {
        if (this.drawerShows(svc.id)) {
          this.hospitalLinks.update((list) => [...list, created]);
        }
        this.adjustHospitalLinkCount(svc.id, 1);
        this.setLinkBusy(svc.id, row.hospitalId, false);
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.HOSPITAL_LINKED', { name: row.hospitalName }),
        );
      },
      error: (err) => {
        this.setLinkBusy(svc.id, row.hospitalId, false);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.HOSPITAL_LINK_FAILED'));
      },
    });
  }

  /** D2: enables or disables an existing link in place — never a second create. */
  setHospitalLinkEnabled(row: HospitalLinkRow, enabled: boolean): void {
    const svc = this.selectedService();
    if (!svc || !row.link) return;
    this.setLinkBusy(svc.id, row.hospitalId, true);
    this.platformSvc.setHospitalLinkEnabled(row.hospitalId, svc.id, enabled).subscribe({
      next: (updated) => {
        if (this.drawerShows(svc.id)) {
          this.hospitalLinks.update((list) =>
            list.map((l) => (l.hospitalId === row.hospitalId ? updated : l)),
          );
        }
        this.setLinkBusy(svc.id, row.hospitalId, false);
        this.toast.success(
          this.translate.instant(
            enabled
              ? 'PLATFORM.TOAST.HOSPITAL_LINK_ENABLED'
              : 'PLATFORM.TOAST.HOSPITAL_LINK_DISABLED',
            { name: row.hospitalName },
          ),
        );
      },
      error: (err) => {
        this.setLinkBusy(svc.id, row.hospitalId, false);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.HOSPITAL_LINK_UPDATE_FAILED'));
      },
    });
  }

  /** Deletes the link and its settings, after the user confirms the hard delete. */
  unlinkHospital(row: HospitalLinkRow): void {
    const svc = this.selectedService();
    if (!svc || !row.link) return;
    if (
      !window.confirm(
        this.translate.instant('PLATFORM.CONFIRM_UNLINK', { hospital: row.hospitalName }),
      )
    ) {
      return;
    }
    this.setLinkBusy(svc.id, row.hospitalId, true);
    this.platformSvc.unlinkHospital(row.hospitalId, svc.id).subscribe({
      next: () => {
        if (this.drawerShows(svc.id)) {
          this.hospitalLinks.update((list) => list.filter((l) => l.hospitalId !== row.hospitalId));
        }
        this.adjustHospitalLinkCount(svc.id, -1);
        this.setLinkBusy(svc.id, row.hospitalId, false);
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.HOSPITAL_UNLINKED', { name: row.hospitalName }),
        );
      },
      error: (err) => {
        this.setLinkBusy(svc.id, row.hospitalId, false);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.HOSPITAL_UNLINK_FAILED'));
      },
    });
  }

  private adjustHospitalLinkCount(serviceId: string, delta: number): void {
    const bump = (s: OrgServiceResponse): OrgServiceResponse =>
      s.id === serviceId
        ? { ...s, hospitalLinkCount: Math.max(0, (s.hospitalLinkCount ?? 0) + delta) }
        : s;
    this.orgServices.update((list) => list.map(bump));
    const selected = this.selectedService();
    if (selected) this.selectedService.set(bump(selected));
  }

  /* ══════════════════════════════════════════
     Catalog
     ══════════════════════════════════════════ */

  loadCatalog(): void {
    this.loading.set(true);
    this.platformSvc.getCatalog(true).subscribe({
      next: (items) => {
        this.catalog.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PLATFORM.TOAST.CATALOG_LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  /** Opens the entry; provisioning happens from here, after choosing the organization (D1). */
  openCatalogDetail(item: CatalogItem): void {
    this.selectedCatalogItem.set(item);
    this.provisionOrgId.set(null);
    this.catalogDetailOpen.set(true);
    this.ensureOrganizations();
  }

  closeCatalogDetail(): void {
    this.catalogDetailOpen.set(false);
    this.selectedCatalogItem.set(null);
    this.provisionOrgId.set(null);
  }

  /**
   * D1: provisions to the organization the user picked in the drawer — no
   * silent fallback to the Services tab's selection or the first
   * organization. D13: a disabled catalog entry is never sent.
   */
  provisionCatalogItem(item: CatalogItem): void {
    const orgId = this.provisionOrgId();
    if (!orgId || !item.enabled || this.saving()) return;
    const orgName = this.getOrgName(orgId);

    const request: OrgServiceRegisterRequest = {
      serviceType: item.serviceType,
      provider: item.provider || undefined,
      baseUrl: item.baseUrl || undefined,
      documentationUrl: item.documentationUrl || undefined,
      managedByPlatform: item.managedByPlatform,
      ownership: item.defaultOwnership ?? undefined,
      metadata: item.defaultMetadata ?? undefined,
    };

    this.saving.set(true);
    this.platformSvc.registerOrgService(orgId, request).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeCatalogDetail();
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.PROVISIONED_TO_ORG', {
            name: item.displayName,
            org: orgName,
          }),
        );
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.PROVISION_FAILED'));
      },
    });
  }

  /** A real, reachable http(s) address — not a seeded placeholder host (D12). */
  isLiveUrl(url: string | null | undefined): boolean {
    if (!url) return false;
    let parsed: URL;
    try {
      parsed = new URL(url);
    } catch {
      return false;
    }
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return false;
    return !PLACEHOLDER_HOSTS.some((pattern) => pattern.test(parsed.hostname));
  }

  /** The first live documentation address the entry has, if any. */
  docsUrl(item: CatalogItem): string | null {
    for (const url of [item.documentationUrl, item.onboardingGuideUrl]) {
      if (this.isLiveUrl(url)) return url;
    }
    return null;
  }

  openDocs(item: CatalogItem): void {
    const url = this.docsUrl(item);
    if (url) {
      window.open(url, '_blank', 'noopener');
    } else {
      this.toast.info(this.translate.instant('PLATFORM.TOAST.NO_DOCS_URL'));
    }
  }

  openSandbox(item: CatalogItem): void {
    if (this.isLiveUrl(item.sandboxUrl)) {
      window.open(item.sandboxUrl, '_blank', 'noopener');
    } else {
      this.toast.info(this.translate.instant('PLATFORM.TOAST.NO_SANDBOX'));
    }
  }

  /* ══════════════════════════════════════════
     Release Windows
     ══════════════════════════════════════════ */

  /** D6: the list comes from the server, so it survives a reload. */
  loadReleases(): void {
    this.releasesLoading.set(true);
    this.releasesError.set(false);
    this.platformSvc.listReleaseWindows().subscribe({
      next: (windows) => {
        this.releases.set(windows);
        this.releasesLoading.set(false);
      },
      error: (err) => {
        this.releasesLoading.set(false);
        this.releasesError.set(true);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.RELEASES_LOAD_FAILED'));
      },
    });
  }

  openReleaseForm(): void {
    this.releaseForm.set({ ...EMPTY_RELEASE_FORM });
    this.releaseFormOpen.set(true);
  }

  closeReleaseForm(): void {
    this.releaseFormOpen.set(false);
  }

  scheduleRelease(): void {
    const f = this.releaseForm();
    if (!f.name || !f.startsAt || !f.endsAt || !f.environment) {
      this.toast.error(this.translate.instant('PLATFORM.TOAST.RELEASE_REQUIRED_FIELDS'));
      return;
    }

    const request: ReleaseWindowRequest = {
      name: f.name,
      description: f.description || undefined,
      environment: f.environment,
      startsAt: f.startsAt,
      endsAt: f.endsAt,
      freezeChanges: f.freezeChanges,
      ownerTeam: f.ownerTeam || undefined,
      notes: f.notes || undefined,
    };

    this.saving.set(true);
    this.platformSvc.scheduleReleaseWindow(request).subscribe({
      next: (created) => {
        this.releaseFormOpen.set(false);
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.RELEASE_SCHEDULED', { name: created.name }),
        );
        this.loadReleases();
        this.loadDashboard();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(this.errorMessage(err, 'PLATFORM.TOAST.RELEASE_SCHEDULE_FAILED'));
      },
    });
  }

  /* ══════════════════════════════════════════
     Helpers
     ══════════════════════════════════════════ */

  /**
   * D11: the toast for a failed request. A validation 400 carries
   * `fieldErrors`; those are what the user can fix, so they are shown with
   * the label of the field that failed instead of the generic
   * "Validation errors in request".
   */
  errorMessage(err: unknown, fallbackKey: string): string {
    const body = (err as { error?: ApiErrorBody | null } | null)?.error;
    const fieldErrors = body?.fieldErrors;
    if (fieldErrors && Object.keys(fieldErrors).length > 0) {
      const details = Object.entries(fieldErrors)
        .map(([field, message]) => {
          const labelKey = FIELD_LABEL_KEYS[field];
          const label = labelKey ? this.translate.instant(labelKey) : field;
          return `${label}: ${message}`;
        })
        .join('; ');
      return this.translate.instant('PLATFORM.TOAST.VALIDATION_FAILED', { errors: details });
    }
    if (typeof body?.message === 'string' && body.message.trim()) {
      return body.message;
    }
    return this.translate.instant(fallbackKey);
  }

  statusClass(status: string): string {
    switch (status) {
      case 'ON_TRACK':
        return 'on-track';
      case 'AT_RISK':
        return 'at-risk';
      case 'BLOCKED':
        return 'blocked';
      default:
        return '';
    }
  }

  serviceStatusClass(status: PlatformServiceStatus): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-active';
      case 'PILOT':
        return 'status-pilot';
      case 'PENDING':
        return 'status-pending';
      case 'INACTIVE':
        return 'status-inactive';
      case 'DECOMMISSIONED':
        return 'status-decommissioned';
      default:
        return '';
    }
  }

  releaseStatusClass(status: string): string {
    switch (status) {
      case 'SCHEDULED':
        return 'release-scheduled';
      case 'IN_PROGRESS':
        return 'release-in-progress';
      case 'COMPLETED':
        return 'release-completed';
      case 'CANCELLED':
        return 'release-cancelled';
      default:
        return '';
    }
  }

  serviceTypeIcon(type: PlatformServiceType): string {
    const icons: Record<string, string> = {
      EHR: 'medical_information',
      BILLING: 'payments',
      INVENTORY: 'inventory_2',
      LIMS: 'science',
      ANALYTICS: 'analytics',
      CLINICAL_ANALYTICS: 'monitoring',
      REMOTE_MONITORING: 'sensors',
      PEDIATRIC_MESSAGING: 'child_care',
      ORTHO_IMAGING: 'radiology',
      RESP_TELEMED: 'video_call',
    };
    return icons[type] ?? 'extension';
  }

  serviceTypeLabel(type: PlatformServiceType): string {
    // NOTE: per-type labels live under PLATFORM.SERVICE_TYPES.* (plural).
    // The singular PLATFORM.SERVICE_TYPE is reserved as the field-label
    // string used by `'PLATFORM.SERVICE_TYPE' | translate` in platform.html.
    // Mixing them would render "[object Object]" in templates (Copilot
    // review on PR #256).
    const keys: Record<string, string> = {
      EHR: 'PLATFORM.SERVICE_TYPES.EHR',
      BILLING: 'PLATFORM.SERVICE_TYPES.BILLING',
      INVENTORY: 'PLATFORM.SERVICE_TYPES.INVENTORY',
      LIMS: 'PLATFORM.SERVICE_TYPES.LIS',
      ANALYTICS: 'PLATFORM.SERVICE_TYPES.ANALYTICS',
      CLINICAL_ANALYTICS: 'PLATFORM.SERVICE_TYPES.CLINICAL_ANALYTICS',
      REMOTE_MONITORING: 'PLATFORM.SERVICE_TYPES.REMOTE_MONITORING',
      PEDIATRIC_MESSAGING: 'PLATFORM.SERVICE_TYPES.PEDIATRIC_MESSAGING',
      ORTHO_IMAGING: 'PLATFORM.SERVICE_TYPES.ORTHOPEDIC_IMAGING',
      RESP_TELEMED: 'PLATFORM.SERVICE_TYPES.RESPIRATORY_TELEHEALTH',
    };
    const key = keys[type];
    return key ? this.translate.instant(key) : type;
  }

  /**
   * D8: a task offers the page that holds what it measures — nothing on
   * this page runs a job, so "Run now / Investigate / Unblock" are gone.
   * Unread notifications have no page here, so that task offers none.
   */
  taskAction(task: Pick<AutomationTask, 'id'>): TaskAction | null {
    switch (task.id) {
      case 'release-windows':
        return { labelKey: 'PLATFORM.TASK_ACTION.OPEN_RELEASES', icon: 'event', view: 'releases' };
      case 'disabled-links':
        return {
          labelKey: 'PLATFORM.TASK_ACTION.OPEN_SERVICES',
          icon: 'settings_applications',
          view: 'services',
        };
      default:
        return null;
    }
  }

  viewAutomationDetails(
    task: Pick<AutomationTask, 'title' | 'nextAction' | 'metricLabel' | 'metricValue'>,
  ): void {
    this.toast.info(
      this.translate.instant('PLATFORM.TOAST.TASK_DETAILS', {
        title: task.title,
        nextAction: task.nextAction,
        metricLabel: task.metricLabel,
        metricValue: task.metricValue,
      }),
    );
  }

  getOrgName(orgId: string | null): string {
    if (!orgId) return '';
    const org = this.organizations().find((o) => o.id === orgId);
    return org?.name ?? orgId;
  }

  /* ── Form field updaters (templates can't use arrow fns) ── */

  updateServiceField(field: keyof ServiceForm, value: string | boolean): void {
    this.serviceForm.update((f) => ({ ...f, [field]: value }));
  }

  updateRegisterField(field: keyof ServiceForm, value: string | boolean): void {
    this.registerForm.update((f) => ({ ...f, [field]: value }));
  }

  updateReleaseField(field: keyof ReleaseForm, value: string | boolean): void {
    this.releaseForm.update((f) => ({ ...f, [field]: value }));
  }
}
