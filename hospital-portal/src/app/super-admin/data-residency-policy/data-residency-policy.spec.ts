import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { DataResidencyPolicyComponent } from './data-residency-policy';
import { RegionPolicyService } from '../../services/region-policy.service';
import { RegionPolicyRow } from '../../services/region-policy.model';

const fakePolicies = (): RegionPolicyRow[] => [
  {
    region: 'BF',
    retentionDays: null,
    defaultExportFormat: null,
    targetDeploymentUrl: null,
    updatedAt: '2026-05-01T00:00:00Z',
    updatedBy: 'system',
  },
  {
    region: 'EU',
    retentionDays: 730,
    defaultExportFormat: 'GDPR_PORTABILITY',
    targetDeploymentUrl: null,
    updatedAt: '2026-05-02T08:30:00Z',
    updatedBy: 'alice@example.com',
  },
];

describe('DataResidencyPolicyComponent (MVP-9c)', () => {
  let service: jasmine.SpyObj<RegionPolicyService>;

  function setup(): DataResidencyPolicyComponent {
    TestBed.configureTestingModule({
      imports: [DataResidencyPolicyComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: RegionPolicyService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(DataResidencyPolicyComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<RegionPolicyService>('RegionPolicyService', [
      'list',
      'get',
      'update',
    ]);
  });

  it('loads the policy table on init', () => {
    service.list.and.returnValue(of(fakePolicies()));

    const cmp = setup();

    expect(service.list).toHaveBeenCalledTimes(1);
    expect(cmp.loading()).toBeFalse();
    expect(cmp.errored()).toBeFalse();
    expect(cmp.rows().length).toBe(2);
  });

  it('shows the error panel when the list call fails', () => {
    service.list.and.returnValue(throwError(() => new Error('boom')));

    const cmp = setup();

    expect(cmp.errored()).toBeTrue();
    expect(cmp.rows()).toEqual([]);
  });

  it('startEdit() seeds the editing state from the row', () => {
    service.list.and.returnValue(of(fakePolicies()));

    const cmp = setup();
    cmp.startEdit(fakePolicies()[1]);

    const edit = cmp.editing();
    expect(edit?.region).toBe('EU');
    expect(edit?.retentionDays).toBe(730);
    expect(edit?.defaultExportFormat).toBe('GDPR_PORTABILITY');
    expect(edit?.busy).toBeFalse();
  });

  it('submitEdit() patches the row in place on success and clears the editor', () => {
    service.list.and.returnValue(of(fakePolicies()));
    const updated: RegionPolicyRow = {
      region: 'EU',
      retentionDays: 365,
      defaultExportFormat: 'GDPR_PORTABILITY',
      targetDeploymentUrl: 'https://eu.hms.example/api',
      updatedAt: '2026-05-03T08:00:00Z',
      updatedBy: 'alice@example.com',
    };
    service.update.and.returnValue(of(updated));

    const cmp = setup();
    cmp.startEdit(fakePolicies()[1]);
    cmp.patchEdit('retentionDays', 365);
    cmp.patchEdit('targetDeploymentUrl', 'https://eu.hms.example/api');
    cmp.submitEdit();

    expect(service.update).toHaveBeenCalledWith('EU', {
      retentionDays: 365,
      defaultExportFormat: 'GDPR_PORTABILITY',
      targetDeploymentUrl: 'https://eu.hms.example/api',
    });
    expect(cmp.editing()).toBeNull();
    const euRow = cmp.rows().find((r) => r.region === 'EU');
    expect(euRow?.retentionDays).toBe(365);
    expect(euRow?.targetDeploymentUrl).toBe('https://eu.hms.example/api');
  });

  it('submitEdit() surfaces an errorKey when the update fails and keeps the form open', () => {
    service.list.and.returnValue(of(fakePolicies()));
    service.update.and.returnValue(throwError(() => new Error('400')));

    const cmp = setup();
    cmp.startEdit(fakePolicies()[1]);
    cmp.submitEdit();

    expect(cmp.editing()).not.toBeNull();
    expect(cmp.editing()?.errorKey).toBe('REGION_POLICY.ERROR.UPDATE_FAILED');
    expect(cmp.editing()?.busy).toBeFalse();
  });

  it('cancelEdit() drops the editing state', () => {
    service.list.and.returnValue(of(fakePolicies()));

    const cmp = setup();
    cmp.startEdit(fakePolicies()[0]);
    cmp.cancelEdit();

    expect(cmp.editing()).toBeNull();
  });

  it('blank export-format and deployment-URL inputs are normalized to null on save', () => {
    service.list.and.returnValue(of(fakePolicies()));
    service.update.and.returnValue(of(fakePolicies()[0]));

    const cmp = setup();
    cmp.startEdit(fakePolicies()[0]);
    cmp.patchEdit('defaultExportFormat', '   ');
    cmp.patchEdit('targetDeploymentUrl', '');
    cmp.submitEdit();

    expect(service.update).toHaveBeenCalledWith('BF', {
      retentionDays: null,
      defaultExportFormat: null,
      targetDeploymentUrl: null,
    });
  });
});
