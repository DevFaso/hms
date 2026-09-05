import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { forkJoin, catchError, of } from 'rxjs';
import { ToastService } from '../core/toast.service';
import { OrganizationService, OrganizationResponse } from '../services/organization.service';
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
  PlatformSnapshot,
} from '../services/platform.service';

/* ── View state types ── */

type ActiveView = 'dashboard' | 'services' | 'catalog' | 'releases';

interface ServiceForm {
  serviceType: PlatformServiceType;
  provider: string;
  baseUrl: string;
  documentationUrl: string;
  apiKeyReference: string;
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

const EMPTY_SERVICE_FORM: ServiceForm = {
  serviceType: 'EHR',
  provider: '',
  baseUrl: '',
  documentationUrl: '',
  apiKeyReference: '',
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

@Component({
  selector: 'app-platform',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './platform.html',
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
  snapshot = signal<PlatformSnapshot | null>(null);

  /* Services */
  organizations = signal<OrganizationResponse[]>([]);
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

  /* Catalog */
  catalog = signal<CatalogItem[]>([]);
  catalogSearchTerm = signal('');
  selectedCatalogItem = signal<CatalogItem | null>(null);
  catalogDetailOpen = signal(false);

  /* Releases */
  releases = signal<ReleaseWindowResponse[]>([]);
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
        break;
    }
  }

  /* ══════════════════════════════════════════
     Dashboard
     ══════════════════════════════════════════ */

  loadDashboard(): void {
    this.loading.set(true);
    this.error.set(null);

    forkJoin({
      summary: this.platformSvc.getSummary().pipe(catchError(() => of(null))),
      catalog: this.platformSvc.getCatalog(true).pipe(catchError(() => of([] as CatalogItem[]))),
      snapshot: this.platformSvc.getSnapshot().pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ summary, catalog, snapshot }) => {
        this.summary.set(summary);
        this.catalog.set(catalog);
        this.snapshot.set(snapshot);
        this.loading.set(false);
        if (!summary) {
          this.error.set('Failed to load platform summary.');
        }
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Failed to load platform data. Please try again.');
      },
    });
  }

  exportSnapshot(): void {
    this.saving.set(true);
    this.platformSvc.getSnapshot().subscribe({
      next: (snap) => {
        this.snapshot.set(snap);
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
     Organization Services
     ══════════════════════════════════════════ */

  loadOrganizations(): void {
    this.servicesLoading.set(true);
    this.orgSvc.list(0, 50).subscribe({
      next: (page) => {
        this.organizations.set(page.content);
        this.servicesLoading.set(false);
        if (page.content.length > 0 && !this.selectedOrgId()) {
          this.selectOrg(page.content[0].id);
        }
      },
      error: () => {
        this.toast.error(this.translate.instant('PLATFORM.TOAST.ORGS_LOAD_FAILED'));
        this.servicesLoading.set(false);
      },
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

  openServiceDetail(service: OrgServiceResponse): void {
    this.selectedService.set(service);
    this.serviceDrawerOpen.set(true);
    this.editingService.set(false);
    this.loadHospitalLinksForService(service);
  }

  closeServiceDrawer(): void {
    this.serviceDrawerOpen.set(false);
    this.selectedService.set(null);
    this.editingService.set(false);
  }

  loadHospitalLinksForService(service: OrgServiceResponse): void {
    const org = this.organizations().find((o) => o.id === service.organizationId);
    if (!org || !org.hospitals?.length) {
      this.hospitalLinks.set([]);
      return;
    }
    this.linksLoading.set(true);
    const hospitalId = org.hospitals[0].id;
    this.platformSvc.listHospitalLinks(hospitalId).subscribe({
      next: (links) => {
        this.hospitalLinks.set(links.filter((l) => l.organizationServiceId === service.id));
        this.linksLoading.set(false);
      },
      error: () => {
        this.hospitalLinks.set([]);
        this.linksLoading.set(false);
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
      // value. Blank means keep; typing replaces.
      apiKeyReference: '',
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

  saveService(): void {
    const svc = this.selectedService();
    const orgId = this.selectedOrgId();
    if (!svc || !orgId) return;

    const f = this.serviceForm();
    const request: OrgServiceUpdateRequest = {
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
    this.platformSvc.updateOrgService(orgId, svc.id, request).subscribe({
      next: (updated) => {
        this.selectedService.set(updated);
        this.orgServices.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
        this.editingService.set(false);
        this.saving.set(false);
        this.toast.success(this.translate.instant('PLATFORM.TOAST.SERVICE_UPDATED'));
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PLATFORM.TOAST.SERVICE_UPDATE_FAILED'),
        );
      },
    });
  }

  changeServiceStatus(service: OrgServiceResponse, newStatus: PlatformServiceStatus): void {
    const orgId = this.selectedOrgId();
    if (!orgId) return;

    this.saving.set(true);
    this.platformSvc.updateOrgService(orgId, service.id, { status: newStatus }).subscribe({
      next: (updated) => {
        this.orgServices.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
        if (this.selectedService()?.id === updated.id) {
          this.selectedService.set(updated);
        }
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.STATUS_CHANGED', { status: newStatus }),
        );
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PLATFORM.TOAST.STATUS_CHANGE_FAILED'),
        );
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
          this.translate.instant('PLATFORM.TOAST.SERVICE_REGISTERED', { name: f.serviceType }),
        );
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PLATFORM.TOAST.SERVICE_REGISTER_FAILED'),
        );
      },
    });
  }

  toggleHospitalLink(link: HospitalServiceLink): void {
    const svc = this.selectedService();
    if (!svc) return;

    if (link.enabled) {
      this.platformSvc.unlinkHospital(link.hospitalId, svc.id).subscribe({
        next: () => {
          this.hospitalLinks.update((list) => list.filter((l) => l.id !== link.id));
          this.toast.success(
            this.translate.instant('PLATFORM.TOAST.HOSPITAL_UNLINKED', { name: link.hospitalName }),
          );
        },
        error: () =>
          this.toast.error(this.translate.instant('PLATFORM.TOAST.HOSPITAL_UNLINK_FAILED')),
      });
    } else {
      this.platformSvc.linkHospital(link.hospitalId, svc.id, { enabled: true }).subscribe({
        next: (updated) => {
          this.hospitalLinks.update((list) => list.map((l) => (l.id === link.id ? updated : l)));
          this.toast.success(
            this.translate.instant('PLATFORM.TOAST.HOSPITAL_LINKED', { name: link.hospitalName }),
          );
        },
        error: () =>
          this.toast.error(this.translate.instant('PLATFORM.TOAST.HOSPITAL_LINK_FAILED')),
      });
    }
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

  openCatalogDetail(item: CatalogItem): void {
    this.selectedCatalogItem.set(item);
    this.catalogDetailOpen.set(true);
  }

  closeCatalogDetail(): void {
    this.catalogDetailOpen.set(false);
    this.selectedCatalogItem.set(null);
  }

  provisionCatalogItem(item: CatalogItem): void {
    const orgId = this.selectedOrgId() || this.organizations()?.[0]?.id;
    if (!orgId) {
      // Load orgs first
      this.orgSvc.list(0, 1).subscribe({
        next: (page) => {
          if (page.content.length > 0) {
            this.organizations.set(page.content);
            this.selectedOrgId.set(page.content[0].id);
            this.doProvision(page.content[0].id, item);
          } else {
            this.toast.error(this.translate.instant('PLATFORM.TOAST.NO_ORGS_AVAILABLE'));
          }
        },
        error: () => this.toast.error(this.translate.instant('PLATFORM.TOAST.ORGS_LOAD_FAILED')),
      });
      return;
    }
    this.doProvision(orgId, item);
  }

  private doProvision(orgId: string, item: CatalogItem): void {
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
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.PROVISIONED', { name: item.displayName }),
        );
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PLATFORM.TOAST.PROVISION_FAILED'),
        );
      },
    });
  }

  openDocs(item: CatalogItem): void {
    if (item.documentationUrl) {
      window.open(item.documentationUrl, '_blank');
    } else if (item.onboardingGuideUrl) {
      window.open(item.onboardingGuideUrl, '_blank');
    } else if (item.baseUrl) {
      window.open(item.baseUrl, '_blank');
    } else {
      this.toast.info(this.translate.instant('PLATFORM.TOAST.NO_DOCS_URL'));
    }
  }

  openSandbox(item: CatalogItem): void {
    if (item.sandboxUrl) {
      window.open(item.sandboxUrl, '_blank');
    } else {
      this.toast.info(this.translate.instant('PLATFORM.TOAST.NO_SANDBOX'));
    }
  }

  /* ══════════════════════════════════════════
     Release Windows
     ══════════════════════════════════════════ */

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
        this.releases.update((list) => [created, ...list]);
        this.releaseFormOpen.set(false);
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('PLATFORM.TOAST.RELEASE_SCHEDULED', { name: created.name }),
        );
        this.loadDashboard();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PLATFORM.TOAST.RELEASE_SCHEDULE_FAILED'),
        );
      },
    });
  }

  /* ══════════════════════════════════════════
     Helpers
     ══════════════════════════════════════════ */

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

  taskActionLabel(status: string): string {
    let key: string;
    switch (status) {
      case 'ON_TRACK':
        key = 'PLATFORM.TASK_ACTION.RUN_NOW';
        break;
      case 'AT_RISK':
        key = 'PLATFORM.TASK_ACTION.INVESTIGATE';
        break;
      case 'BLOCKED':
        key = 'PLATFORM.TASK_ACTION.UNBLOCK';
        break;
      default:
        key = 'PLATFORM.TASK_ACTION.RUN';
    }
    return this.translate.instant(key);
  }

  taskActionIcon(status: string): string {
    switch (status) {
      case 'ON_TRACK':
        return 'play_arrow';
      case 'AT_RISK':
        return 'search';
      case 'BLOCKED':
        return 'lock_open';
      default:
        return 'play_arrow';
    }
  }

  runAutomationTask(task: { id: string }): void {
    if (task.id === 'release-windows') {
      this.switchView('releases');
    } else {
      this.switchView('services');
    }
  }

  viewAutomationDetails(task: {
    title: string;
    nextAction: string;
    metricLabel: string;
    metricValue: string;
    lastRun: string;
  }): void {
    this.toast.info(
      `${task.title}: ${task.nextAction} | ${task.metricLabel}: ${task.metricValue} | Last: ${task.lastRun}`,
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
