import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { DataResidencyComponent } from './data-residency';
import { DataResidencyService } from '../../services/data-residency.service';
import { OrganizationRegion, OrganizationRegionRow } from '../../services/data-residency.model';

const REGIONS: OrganizationRegion[] = [
  'BF',
  'CI',
  'SN',
  'GA',
  'CM',
  'BJ',
  'TG',
  'ML',
  'NE',
  'ML_OAPI',
  'EU',
  'US',
  'OTHER',
];

const fakeRows = (): OrganizationRegionRow[] => [
  { organizationId: 'o1', organizationName: 'Alpha Clinic', organizationCode: 'ACL', region: 'BF' },
  {
    organizationId: 'o2',
    organizationName: 'Beta Hospital',
    organizationCode: 'BHP',
    region: 'BF',
  },
  { organizationId: 'o3', organizationName: 'Gamma Health', organizationCode: 'GMH', region: 'SN' },
];

describe('DataResidencyComponent (MVP-9)', () => {
  let service: jasmine.SpyObj<DataResidencyService>;

  function setup(): DataResidencyComponent {
    TestBed.configureTestingModule({
      imports: [DataResidencyComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: DataResidencyService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(DataResidencyComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<DataResidencyService>('DataResidencyService', [
      'listAvailableRegions',
      'getRegionSnapshot',
      'getRegion',
      'updateRegion',
    ]);
    service.listAvailableRegions.and.returnValue(of(REGIONS));
    service.getRegionSnapshot.and.returnValue(of(fakeRows()));
  });

  it('renders the snapshot rows when loading succeeds', () => {
    const cmp = setup();

    expect(cmp.loading()).toBeFalse();
    expect(cmp.errored()).toBeFalse();
    expect(cmp.rows().length).toBe(3);
    expect(cmp.availableRegions().length).toBe(REGIONS.length);
  });

  it('shows the error panel when the snapshot call fails', () => {
    service.getRegionSnapshot.and.returnValue(throwError(() => new Error('boom')));

    const cmp = setup();

    expect(cmp.loading()).toBeFalse();
    expect(cmp.errored()).toBeTrue();
    expect(cmp.rows().length).toBe(0);
  });

  it('builds a distribution map keyed by region with descending counts', () => {
    const cmp = setup();
    const entries = cmp.distributionEntries();

    expect(entries.length).toBe(2);
    expect(entries[0].region).toBe('BF');
    expect(entries[0].count).toBe(2);
    expect(entries[1].region).toBe('SN');
    expect(entries[1].count).toBe(1);
  });

  it('filters visible rows by the selected region', () => {
    const cmp = setup();

    expect(cmp.visibleRows().length).toBe(3);
    cmp.setFilter('SN');
    expect(cmp.visibleRows().length).toBe(1);
    expect(cmp.visibleRows()[0].organizationCode).toBe('GMH');
    cmp.setFilter('');
    expect(cmp.visibleRows().length).toBe(3);
  });

  it('opens an edit form when retag is clicked, populated with the current row', () => {
    const cmp = setup();
    const target = cmp.rows()[0];

    cmp.startEdit(target);
    const state = cmp.editing();

    expect(state).not.toBeNull();
    expect(state!.organizationId).toBe(target.organizationId);
    expect(state!.region).toBe(target.region);
    expect(state!.busy).toBeFalse();
  });

  it('persists the region update and patches the row in place on success', () => {
    const updated: OrganizationRegionRow = {
      organizationId: 'o1',
      organizationName: 'Alpha Clinic',
      organizationCode: 'ACL',
      region: 'EU',
    };
    service.updateRegion.and.returnValue(of(updated));

    const cmp = setup();
    cmp.startEdit(cmp.rows()[0]);
    cmp.patchEdit('region', 'EU');
    cmp.patchEdit('reason', 'GDPR migration');
    cmp.submitEdit();

    expect(service.updateRegion).toHaveBeenCalledWith('o1', {
      region: 'EU',
      reason: 'GDPR migration',
    });
    expect(cmp.editing()).toBeNull();
    expect(cmp.rows().find((r) => r.organizationId === 'o1')!.region).toBe('EU');
  });

  it('keeps the edit form open and surfaces an error key when the update fails', () => {
    service.updateRegion.and.returnValue(throwError(() => new Error('boom')));

    const cmp = setup();
    cmp.startEdit(cmp.rows()[0]);
    cmp.patchEdit('region', 'EU');
    cmp.submitEdit();

    const state = cmp.editing();
    expect(state).not.toBeNull();
    expect(state!.busy).toBeFalse();
    expect(state!.error).toBe('ORG_REGION.ERROR.UPDATE_FAILED');
  });

  it('cancels the edit form and discards in-flight changes', () => {
    const cmp = setup();
    cmp.startEdit(cmp.rows()[0]);
    cmp.patchEdit('region', 'US');
    cmp.cancelEdit();

    expect(cmp.editing()).toBeNull();
    expect(cmp.rows()[0].region).toBe('BF');
  });
});
