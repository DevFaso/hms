import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError, Observable } from 'rxjs';

import { PlatformComponent, HospitalLinkRow } from './platform';
import {
  CatalogItem,
  HospitalServiceLink,
  OrgServiceResponse,
  PlatformService,
  PlatformSummary,
  ReleaseWindowResponse,
} from '../services/platform.service';
import {
  OrganizationPage,
  OrganizationResponse,
  OrganizationService,
} from '../services/organization.service';
import { ToastService } from '../core/toast.service';

function org(
  id: string,
  name: string,
  hospitals: { id: string; name: string }[] = [],
): OrganizationResponse {
  return {
    id,
    name,
    code: id,
    description: '',
    type: 'HOSPITAL_CHAIN',
    active: true,
    createdAt: '',
    updatedAt: '',
    primaryContactEmail: '',
    primaryContactPhone: '',
    defaultTimezone: '',
    onboardingNotes: '',
    hospitals: hospitals.map((h) => ({ ...h, code: h.id, city: '', active: true })),
  };
}

function page(content: OrganizationResponse[]): OrganizationPage {
  return { content, totalElements: content.length, totalPages: 1, size: 50, number: 0 };
}

function orgService(overrides: Partial<OrgServiceResponse> = {}): OrgServiceResponse {
  return {
    id: 's1',
    organizationId: 'o1',
    serviceType: 'EHR',
    status: 'ACTIVE',
    provider: 'OpenMRS',
    baseUrl: '',
    documentationUrl: '',
    apiKeyReferenceSet: true,
    managedByPlatform: true,
    hospitalLinkCount: 1,
    departmentLinkCount: 0,
    ...overrides,
  };
}

function link(
  hospitalId: string,
  enabled: boolean,
  hospitalName = hospitalId,
): HospitalServiceLink {
  return {
    id: 'l-' + hospitalId,
    hospitalId,
    hospitalName,
    organizationServiceId: 's1',
    serviceType: 'EHR',
    enabled,
    credentialsReferenceSet: false,
    overrideEndpoint: '',
  };
}

function catalogItem(overrides: Partial<CatalogItem> = {}): CatalogItem {
  return {
    id: 'ehr',
    serviceType: 'EHR',
    displayName: 'EHR Core Interop',
    description: '',
    provider: 'FHIR Reference Sandbox',
    baseUrl: 'https://ehr-sandbox.local/api',
    documentationUrl: 'https://docs.internal/platform/ehr',
    sandboxUrl: 'https://ehr-sandbox.local/portal',
    onboardingGuideUrl: '',
    featureFlag: '',
    enabled: true,
    autoProvision: true,
    managedByPlatform: true,
    capabilities: [],
    ...overrides,
  };
}

const SUMMARY: PlatformSummary = {
  modules: [],
  automationTasks: [],
  actions: {
    totalIntegrations: 1,
    pendingIntegrations: 0,
    disabledLinks: 0,
    activeReleaseWindows: 0,
    lastReleaseWindowChangeAt: null,
  },
};

function httpError(status: number, error: unknown): Observable<never> {
  return throwError(() => new HttpErrorResponse({ status, error }));
}

describe('PlatformComponent', () => {
  let platform: jasmine.SpyObj<PlatformService>;
  let orgs: jasmine.SpyObj<OrganizationService>;
  let toast: jasmine.SpyObj<ToastService>;
  let fixture: ComponentFixture<PlatformComponent>;
  let c: PlatformComponent;

  beforeEach(() => {
    platform = jasmine.createSpyObj<PlatformService>('PlatformService', [
      'getSummary',
      'getSnapshot',
      'getCatalog',
      'listOrgServices',
      'updateOrgService',
      'registerOrgService',
      'listServiceHospitalLinks',
      'linkHospital',
      'setHospitalLinkEnabled',
      'unlinkHospital',
      'listReleaseWindows',
      'scheduleReleaseWindow',
    ]);
    orgs = jasmine.createSpyObj<OrganizationService>('OrganizationService', ['list']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);
    platform.getSummary.and.returnValue(of(SUMMARY));
    platform.getCatalog.and.returnValue(of([]));
    platform.listOrgServices.and.returnValue(of([]));
    platform.listServiceHospitalLinks.and.returnValue(of([]));
    platform.listReleaseWindows.and.returnValue(of([]));
    orgs.list.and.returnValue(
      of(
        page([
          org('o1', 'Alpha Health', [{ id: 'h1', name: 'Clinique A' }]),
          org('o2', 'Beta Care'),
        ]),
      ),
    );

    TestBed.configureTestingModule({
      imports: [PlatformComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PlatformService, useValue: platform },
        { provide: OrganizationService, useValue: orgs },
        { provide: ToastService, useValue: toast },
      ],
    });
    fixture = TestBed.createComponent(PlatformComponent);
    c = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  it('loads only the summary for the dashboard, and shows an error when it fails', () => {
    expect(platform.getSummary).toHaveBeenCalledTimes(1);
    expect(platform.getSnapshot).not.toHaveBeenCalled();
    expect(c.summary()).toEqual(SUMMARY);

    platform.getSummary.and.returnValue(httpError(500, {}));
    c.loadDashboard();
    expect(c.summary()).toBeNull();
    expect(c.error()).toBe('PLATFORM.SUMMARY_LOAD_FAILED');
  });

  /* ── D1: explicit organization for provisioning ── */

  describe('provisioning a catalog entry (D1)', () => {
    it('opens with no organization chosen, even when one is selected on the Services tab', () => {
      c.selectedOrgId.set('o1');
      c.openCatalogDetail(catalogItem());

      expect(c.provisionOrgId()).toBeNull();
      expect(orgs.list).toHaveBeenCalled();
    });

    it('sends nothing until an organization is chosen', () => {
      c.organizations.set([org('o1', 'Alpha Health')]);
      c.selectedOrgId.set('o1');
      c.openCatalogDetail(catalogItem());

      c.provisionCatalogItem(catalogItem());

      expect(platform.registerOrgService).not.toHaveBeenCalled();
    });

    it('provisions to the chosen organization and names it in the toast', () => {
      platform.registerOrgService.and.returnValue(of(orgService()));
      c.openCatalogDetail(catalogItem());
      c.provisionOrgId.set('o2');

      c.provisionCatalogItem(catalogItem());

      expect(platform.registerOrgService).toHaveBeenCalledWith(
        'o2',
        jasmine.objectContaining({ serviceType: 'EHR' }),
      );
      expect(toast.success).toHaveBeenCalledWith('PLATFORM.TOAST.PROVISIONED_TO_ORG');
      expect(c.catalogDetailOpen()).toBeFalse();
    });

    it('passes the organization name to the success message', () => {
      platform.registerOrgService.and.returnValue(of(orgService()));
      const instant = spyOn(TestBed.inject(TranslateService), 'instant').and.callFake(
        (key: string | string[]) => key as string,
      );
      c.openCatalogDetail(catalogItem());
      c.provisionOrgId.set('o2');

      c.provisionCatalogItem(catalogItem());

      expect(instant).toHaveBeenCalledWith('PLATFORM.TOAST.PROVISIONED_TO_ORG', {
        name: 'EHR Core Interop',
        org: 'Beta Care',
      });
    });

    it('the drawer button stays disabled until an organization is chosen', () => {
      c.switchView('catalog');
      c.openCatalogDetail(catalogItem());
      fixture.detectChanges();
      const submit = (): HTMLButtonElement =>
        fixture.nativeElement.querySelector('[data-test="provision-submit"]');

      expect(submit().disabled).toBeTrue();

      c.provisionOrgId.set('o1');
      fixture.detectChanges();
      expect(submit().disabled).toBeFalse();
    });
  });

  /* ── D13: disabled catalog entries ── */

  describe('a disabled catalog entry (D13)', () => {
    it('is never sent, whatever organization is chosen', () => {
      const disabled = catalogItem({ enabled: false, serviceType: 'INVENTORY' });
      c.openCatalogDetail(disabled);
      c.provisionOrgId.set('o1');

      c.provisionCatalogItem(disabled);

      expect(platform.registerOrgService).not.toHaveBeenCalled();
    });

    it('disables the card button and explains in the drawer', () => {
      platform.getCatalog.and.returnValue(of([catalogItem({ enabled: false })]));
      c.switchView('catalog');
      fixture.detectChanges();
      const cardButton: HTMLButtonElement = fixture.nativeElement.querySelector(
        '[data-test="catalog-provision"]',
      );
      expect(cardButton.disabled).toBeTrue();

      c.openCatalogDetail(catalogItem({ enabled: false }));
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('[data-test="provision-disabled"]'),
      ).not.toBeNull();
      expect(fixture.nativeElement.querySelector('[data-test="provision-org"]')).toBeNull();
      expect(
        (fixture.nativeElement.querySelector('[data-test="provision-submit"]') as HTMLButtonElement)
          .disabled,
      ).toBeTrue();
    });
  });

  /* ── D12: placeholder URLs are not presented as working links ── */

  it('treats seeded placeholder hosts as not live (D12)', () => {
    expect(c.isLiveUrl('https://docs.internal/platform/ehr')).toBeFalse();
    expect(c.isLiveUrl('https://ehr-sandbox.local/portal')).toBeFalse();
    expect(c.isLiveUrl('https://api.example.com/x')).toBeFalse();
    expect(c.isLiveUrl('javascript:alert(1)')).toBeFalse();
    expect(c.isLiveUrl('not a url')).toBeFalse();
    expect(c.isLiveUrl('')).toBeFalse();
    expect(c.isLiveUrl('https://hl7.org/fhir/R4/')).toBeTrue();
    expect(c.docsUrl(catalogItem())).toBeNull();
    expect(c.docsUrl(catalogItem({ onboardingGuideUrl: 'https://wiki.e-keneya.com/ehr' }))).toBe(
      'https://wiki.e-keneya.com/ehr',
    );
  });

  /* ── D9: services tab reloads for the selected organization ── */

  describe('entering the Services tab (D9)', () => {
    it('reloads the services of an organization that was already selected', () => {
      c.selectedOrgId.set('o2');

      c.switchView('services');

      expect(platform.listOrgServices).toHaveBeenCalledWith('o2');
      expect(c.selectedOrgId()).toBe('o2');
    });

    it('reloads every time the tab is entered', () => {
      c.switchView('services');
      c.switchView('dashboard');
      c.switchView('services');

      expect(platform.listOrgServices).toHaveBeenCalledTimes(2);
    });

    it('falls back to the first organization when the selected one is gone', () => {
      c.selectedOrgId.set('gone');

      c.switchView('services');

      expect(c.selectedOrgId()).toBe('o1');
      expect(platform.listOrgServices).toHaveBeenCalledWith('o1');
    });
  });

  /* ── D3: links for every hospital ── */

  describe('the service drawer’s hospital links (D3)', () => {
    beforeEach(() => {
      c.organizations.set([
        org('o1', 'Alpha Health', [
          { id: 'h1', name: 'Clinique A' },
          { id: 'h2', name: 'Clinique B' },
          { id: 'h3', name: 'Clinique C' },
        ]),
      ]);
    });

    it('reads the links of every hospital with one call', () => {
      platform.listServiceHospitalLinks.and.returnValue(of([link('h2', true), link('h3', false)]));

      c.openServiceDetail(orgService());

      expect(platform.listServiceHospitalLinks).toHaveBeenCalledOnceWith('o1', 's1');
      const rows = c.hospitalRows();
      expect(rows.map((r) => r.hospitalId)).toEqual(['h1', 'h2', 'h3']);
      expect(rows[0].link).toBeNull();
      expect(rows[1].link?.enabled).toBeTrue();
      expect(rows[2].link?.enabled).toBeFalse();
    });

    it('shows an error state rather than "no links" when the read fails', () => {
      platform.listServiceHospitalLinks.and.returnValue(httpError(500, { message: 'boom' }));

      c.openServiceDetail(orgService());

      expect(c.linksError()).toBeTrue();
      expect(toast.error).toHaveBeenCalledWith('boom');
    });
  });

  /* ── D2: enable / disable / unlink ── */

  describe('switching a hospital link (D2)', () => {
    let row: HospitalLinkRow;

    beforeEach(() => {
      platform.listServiceHospitalLinks.and.returnValue(of([link('h1', false, 'Clinique A')]));
      c.organizations.set([org('o1', 'Alpha Health', [{ id: 'h1', name: 'Clinique A' }])]);
      c.orgServices.set([orgService()]);
      c.openServiceDetail(orgService());
      row = c.hospitalRows()[0];
    });

    it('enables a disabled link with an update, never a second create', () => {
      platform.setHospitalLinkEnabled.and.returnValue(of(link('h1', true, 'Clinique A')));

      c.setHospitalLinkEnabled(row, true);

      expect(platform.setHospitalLinkEnabled).toHaveBeenCalledWith('h1', 's1', true);
      expect(platform.linkHospital).not.toHaveBeenCalled();
      expect(c.hospitalRows()[0].link?.enabled).toBeTrue();
      expect(toast.success).toHaveBeenCalledWith('PLATFORM.TOAST.HOSPITAL_LINK_ENABLED');
    });

    it('disables an enabled link without deleting it', () => {
      platform.setHospitalLinkEnabled.and.returnValue(of(link('h1', false, 'Clinique A')));

      c.setHospitalLinkEnabled({ ...row, link: link('h1', true) }, false);

      expect(platform.setHospitalLinkEnabled).toHaveBeenCalledWith('h1', 's1', false);
      expect(platform.unlinkHospital).not.toHaveBeenCalled();
    });

    it('asks before the hard delete, and does nothing when the user declines', () => {
      const confirm = spyOn(window, 'confirm').and.returnValue(false);

      c.unlinkHospital(row);

      expect(confirm).toHaveBeenCalled();
      expect(platform.unlinkHospital).not.toHaveBeenCalled();
    });

    it('deletes after confirmation and updates the count', () => {
      spyOn(window, 'confirm').and.returnValue(true);
      platform.unlinkHospital.and.returnValue(of(undefined));

      c.unlinkHospital(row);

      expect(platform.unlinkHospital).toHaveBeenCalledWith('h1', 's1');
      expect(c.hospitalRows()[0].link).toBeNull();
      expect(c.orgServices()[0].hospitalLinkCount).toBe(0);
    });

    it('creates a link for an unlinked hospital', () => {
      platform.linkHospital.and.returnValue(of(link('h1', true, 'Clinique A')));

      c.linkHospital({ hospitalId: 'h1', hospitalName: 'Clinique A', link: null });

      expect(platform.linkHospital).toHaveBeenCalledWith('h1', 's1', { enabled: true });
    });

    it('reports a failed switch with the server message', () => {
      platform.setHospitalLinkEnabled.and.returnValue(
        httpError(400, { message: 'Hospital is in another organization.' }),
      );

      c.setHospitalLinkEnabled(row, true);

      expect(toast.error).toHaveBeenCalledWith('Hospital is in another organization.');
      expect(c.linkBusy()).toBeNull();
    });
  });

  /* ── D5: clearing fields ── */

  describe('saving an edited service (D5)', () => {
    beforeEach(() => {
      c.openServiceDetail(
        orgService({
          provider: 'OpenMRS',
          ownership: { ownerTeam: 'Interop', serviceLevel: '24x7' },
          metadata: { integrationNotes: 'old', ehrSystem: 'OpenMRS 3' },
        }),
      );
      c.startEditService();
    });

    it('sends an emptied field as "" so the server clears it', () => {
      c.updateServiceField('provider', '');
      c.updateServiceField('ownerTeam', '   ');
      c.updateServiceField('integrationNotes', '');

      const request = c.buildUpdateRequest(c.serviceForm());

      expect(request.provider).toBe('');
      expect(request.ownership?.ownerTeam).toBe('');
      expect(request.metadata).toEqual({ integrationNotes: '' });
    });

    it('keeps the API key when the field is blank, replaces it when typed, clears it on request', () => {
      expect(c.buildUpdateRequest(c.serviceForm()).apiKeyReference).toBeUndefined();
      expect(c.buildUpdateRequest(c.serviceForm()).clearApiKeyReference).toBeUndefined();

      c.updateServiceField('apiKeyReference', ' vault://new ');
      expect(c.buildUpdateRequest(c.serviceForm()).apiKeyReference).toBe('vault://new');

      c.updateServiceField('clearApiKey', true);
      const cleared = c.buildUpdateRequest(c.serviceForm());
      expect(cleared.clearApiKeyReference).toBeTrue();
      expect(cleared.apiKeyReference).toBeUndefined();
    });

    it('saves against the service’s own organization', () => {
      platform.updateOrgService.and.returnValue(of(orgService()));
      c.selectedOrgId.set('some-other-org');

      c.saveService();

      expect(platform.updateOrgService).toHaveBeenCalledWith('o1', 's1', jasmine.any(Object));
      expect(toast.success).toHaveBeenCalledWith('PLATFORM.TOAST.SERVICE_UPDATED');
    });
  });

  /* ── D11: field errors in the toast ── */

  describe('validation errors (D11)', () => {
    it('shows each field error with the label of the field', () => {
      const instant = spyOn(TestBed.inject(TranslateService), 'instant').and.callFake(
        (key: string | string[], params?: { errors?: string }) =>
          params?.errors ? `INVALID ${params.errors}` : (key as string),
      );
      const err = new HttpErrorResponse({
        status: 400,
        error: {
          message: 'Validation errors in request',
          fieldErrors: {
            provider: 'size must be between 0 and 120',
            'ownership.ownerContactEmail': 'must be a well-formed email address',
          },
        },
      });

      const message = c.errorMessage(err, 'PLATFORM.TOAST.SERVICE_UPDATE_FAILED');

      expect(message).toBe(
        'INVALID PLATFORM.PROVIDER: size must be between 0 and 120; PLATFORM.CONTACT_EMAIL: must be a well-formed email address',
      );
      expect(instant).toHaveBeenCalledWith('PLATFORM.TOAST.VALIDATION_FAILED', jasmine.any(Object));
    });

    it('falls back to the raw field name, then to the message, then to the fallback key', () => {
      expect(
        c.errorMessage(
          new HttpErrorResponse({
            status: 400,
            error: { fieldErrors: { enabled: 'must not be null' } },
          }),
          'X',
        ),
      ).toContain('PLATFORM.TOAST.VALIDATION_FAILED');
      expect(
        c.errorMessage(
          new HttpErrorResponse({ status: 409, error: { message: 'Already linked.' } }),
          'X',
        ),
      ).toBe('Already linked.');
      expect(c.errorMessage(new HttpErrorResponse({ status: 500, error: null }), 'FALLBACK')).toBe(
        'FALLBACK',
      );
      expect(c.errorMessage(null, 'FALLBACK')).toBe('FALLBACK');
    });

    it('a failed save toasts the field errors, not the generic message', () => {
      platform.updateOrgService.and.returnValue(
        httpError(400, {
          message: 'Validation errors in request',
          fieldErrors: { baseUrl: 'size must be between 0 and 255' },
        }),
      );
      c.openServiceDetail(orgService());
      c.startEditService();

      c.saveService();

      const shown = toast.error.calls.mostRecent().args[0];
      expect(shown).not.toBe('Validation errors in request');
      expect(shown).toBe('PLATFORM.TOAST.VALIDATION_FAILED');
    });
  });

  /* ── D6: release windows from the server ── */

  describe('the Releases tab (D6)', () => {
    const window1: ReleaseWindowResponse = {
      id: 'r1',
      name: 'Q4 freeze',
      description: '',
      environment: 'production',
      startsAt: '2026-10-10T18:00:00',
      endsAt: '2026-10-10T20:00:00',
      status: 'SCHEDULED',
      freezeChanges: true,
      ownerTeam: '',
      notes: '',
      createdAt: '',
      updatedAt: '',
    };

    it('loads the windows from the server on entry', () => {
      platform.listReleaseWindows.and.returnValue(of([window1]));

      c.switchView('releases');

      expect(platform.listReleaseWindows).toHaveBeenCalled();
      expect(c.releases()).toEqual([window1]);
    });

    it('shows an error state, not an empty list, when the read fails', () => {
      platform.listReleaseWindows.and.returnValue(httpError(500, {}));

      c.switchView('releases');
      fixture.detectChanges();

      expect(c.releasesError()).toBeTrue();
      expect(fixture.nativeElement.querySelector('[data-test="releases-error"]')).not.toBeNull();
    });

    it('re-reads the list after scheduling instead of keeping a session-only copy', () => {
      platform.scheduleReleaseWindow.and.returnValue(of(window1));
      c.updateReleaseField('name', 'Q4 freeze');
      c.updateReleaseField('startsAt', '2026-10-10T18:00');
      c.updateReleaseField('endsAt', '2026-10-10T20:00');

      c.scheduleRelease();

      expect(platform.listReleaseWindows).toHaveBeenCalled();
      expect(c.releaseFormOpen()).toBeFalse();
    });

    it('reports a scheduling refusal with the server message', () => {
      platform.scheduleReleaseWindow.and.returnValue(
        httpError(409, {
          message: 'A release window with this name already exists in this environment.',
        }),
      );
      c.updateReleaseField('name', 'Q4 freeze');
      c.updateReleaseField('startsAt', '2026-10-10T18:00');
      c.updateReleaseField('endsAt', '2026-10-10T20:00');

      c.scheduleRelease();

      expect(toast.error).toHaveBeenCalledWith(
        'A release window with this name already exists in this environment.',
      );
    });
  });

  /* ── D8: honest task actions ── */

  it('offers navigation only, and nothing for a task with no page here (D8)', () => {
    expect(c.taskAction({ id: 'release-windows' })?.view).toBe('releases');
    expect(c.taskAction({ id: 'disabled-links' })?.view).toBe('services');
    expect(c.taskAction({ id: 'unread-notifications' })).toBeNull();
  });
});
