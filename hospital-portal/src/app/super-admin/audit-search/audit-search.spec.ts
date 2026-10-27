import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { SuperAdminAuditSearchComponent } from './audit-search';
import { AuditSearchService } from '../../services/audit-search.service';
import { AuditSavedSearchService } from '../../services/audit-saved-search.service';
import { DataResidencyService } from '../../services/data-residency.service';
import { AggregatedAuditEvent } from '../../services/audit-search.model';
import fr from '../../../assets/i18n/fr.json';

describe('SuperAdminAuditSearchComponent — MVP-8c source toggles', () => {
  let auditSearch: jasmine.SpyObj<AuditSearchService>;
  let savedSearch: jasmine.SpyObj<AuditSavedSearchService>;
  let residency: jasmine.SpyObj<DataResidencyService>;

  function setup(): SuperAdminAuditSearchComponent {
    TestBed.configureTestingModule({
      imports: [SuperAdminAuditSearchComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuditSearchService, useValue: auditSearch },
        { provide: AuditSavedSearchService, useValue: savedSearch },
        { provide: DataResidencyService, useValue: residency },
      ],
    });
    const fixture = TestBed.createComponent(SuperAdminAuditSearchComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    auditSearch = jasmine.createSpyObj<AuditSearchService>('AuditSearchService', [
      'search',
      'exportCsv',
      'searchAggregated',
    ]);
    auditSearch.search.and.returnValue(
      of({ content: [], pageNumber: 0, pageSize: 25, totalElements: 0, totalPages: 0 }),
    );

    savedSearch = jasmine.createSpyObj<AuditSavedSearchService>('AuditSavedSearchService', [
      'list',
      'create',
      'update',
      'delete',
      'migrateLegacyEntries',
    ]);
    savedSearch.list.and.returnValue(of([]));
    savedSearch.migrateLegacyEntries.and.returnValue(of([]));

    residency = jasmine.createSpyObj<DataResidencyService>('DataResidencyService', [
      'listAvailableRegions',
      'getRegionSnapshot',
      'getRegion',
      'updateRegion',
    ]);
    residency.listAvailableRegions.and.returnValue(of([]));
  });

  it('applySaved shows the saved UTC bounds as local picker values, so a re-save round-trips (#948 review)', () => {
    const component = setup();
    const fromUtc = new Date(2026, 9, 24, 8, 30).toISOString().slice(0, 19);
    component.applySaved({
      id: 's1',
      name: 'Morning',
      filter: { fromDate: fromUtc },
    } as never);
    expect(component.fromDate()).toBe('2026-10-24T08:30');
    const [sent] = auditSearch.search.calls.mostRecent().args;
    expect(sent.fromDate).toBe(fromUtc);
  });

  it('reads an audit entity token in the reader language (D619)', () => {
    const c = setup();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { AUDIT_ENTITY_TYPE: { BILLING_INVOICE: 'Facture' } } },
    });
    translate.use('fr');
    expect(c.entityLabel('BILLING_INVOICE')).toBe('Facture');
    expect(c.entityLabel('Unknown Resource')).toBeNull();
  });
  it('reads a SUPPORT or PLATFORM_CONFIG summary in the reader language, and leaves FRONTEND and PERMISSION_MATRIX as sent (QA D894)', () => {
    const c = setup();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    const row = (source: AggregatedAuditEvent['source'], summary: string | null) =>
      ({ source, id: 'r', summary }) as AggregatedAuditEvent;

    expect(c.summaryText(row('SUPPORT', 'Login failed: bad credentials'))).toBe(
      'Échec de connexion : identifiant ou mot de passe incorrect',
    );
    expect(
      c.summaryText(row('SUPPORT', 'Super-admin cross-tenant read: recent-activity (12 rows)')),
    ).toBe('Lecture du super-administrateur sur tous les hôpitaux : activité récente (12 lignes)');
    expect(
      c.summaryText(row('PLATFORM_CONFIG', 'Super-admin global-view request: GET /audit-logs')),
    ).toBe('Requête du super-administrateur sur tous les hôpitaux : GET /audit-logs');
    expect(c.summaryText(row('FRONTEND', 'Login failed: bad credentials'))).toBe(
      'Login failed: bad credentials',
    );
    expect(c.summaryText(row('PERMISSION_MATRIX', '{"role":"ROLE_NURSE"}'))).toBe(
      '{"role":"ROLE_NURSE"}',
    );
    expect(c.summaryText(row('SUPPORT', null))).toBe('');
  });

  it('toggleAggregatedSource adds and removes a source from the active set', () => {
    const cmp = setup();
    // MVP-c3 — PLATFORM_CONFIG joined the default-on set, so the
    // initial selection is now four sources (SUPPORT, FRONTEND,
    // PERMISSION_MATRIX, PLATFORM_CONFIG).
    expect(cmp.aggregatedSources().length).toBe(4);

    cmp.toggleAggregatedSource('FRONTEND');
    expect(cmp.aggregatedSources()).not.toContain('FRONTEND');

    cmp.toggleAggregatedSource('FRONTEND');
    expect(cmp.aggregatedSources()).toContain('FRONTEND');
  });

  it('toggleAggregatedSource refuses to deselect the LAST active source', () => {
    // Copilot review fix — backend treats empty `sources` as "all
    // sources", so an operator who unchecks everything would see the
    // opposite of what they expect. The toggle is a no-op when only
    // one source remains.
    const cmp = setup();
    cmp.toggleAggregatedSource('FRONTEND');
    cmp.toggleAggregatedSource('PERMISSION_MATRIX');
    cmp.toggleAggregatedSource('PLATFORM_CONFIG');
    expect(cmp.aggregatedSources()).toEqual(['SUPPORT']);

    cmp.toggleAggregatedSource('SUPPORT');
    expect(cmp.aggregatedSources()).toEqual(['SUPPORT']);
    expect(cmp.isLastActiveSource('SUPPORT')).toBeTrue();
  });

  it('isLastActiveSource is false when more than one source is selected', () => {
    const cmp = setup();
    expect(cmp.isLastActiveSource('SUPPORT')).toBeFalse();
  });

  it('PLATFORM_CONFIG is in the default-on source set', () => {
    // MVP-c3 — PLATFORM_CONFIG is a logical view over audit_event_logs
    // filtered to platform-administration writes. It appears in the
    // toggle row alongside the other three sources and is on by
    // default so an operator opening the aggregation tab sees a full
    // unified timeline.
    const cmp = setup();
    expect(cmp.aggregatedSources()).toContain('PLATFORM_CONFIG');
    expect(cmp.allSources).toContain('PLATFORM_CONFIG');
  });
});
