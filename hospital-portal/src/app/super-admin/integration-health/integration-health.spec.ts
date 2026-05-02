import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { IntegrationHealthComponent } from './integration-health';
import { IntegrationHealthService } from '../../services/integration-health.service';
import { IntegrationHealthSummary } from '../../services/integration-health.model';

const fakeSummary = (): IntegrationHealthSummary => ({
  totalIntegrations: 2,
  healthyCount: 1,
  degradedCount: 0,
  failingCount: 1,
  noHistoryCount: 0,
  integrations: [
    {
      integrationId: 'eligibility',
      displayName: 'Insurance eligibility & prior-auth',
      serviceType: null,
      provider: 'StubEligibilityProvider',
      enabled: true,
      capabilities: ['Coverage check'],
      rolledUpStatus: 'FAILING',
      organizations: [
        {
          organizationId: 'o1',
          organizationName: 'Korle Bu',
          status: 'FAILING',
          lastSuccessAt: '2026-05-01T08:00:00Z',
          lastFailureAt: '2026-05-02T07:55:00Z',
          lastErrorMessage: 'Payer timeout',
          successCount24h: 1,
          failureCount24h: 4,
          updatedAt: '2026-05-02T07:55:00Z',
        },
      ],
    },
    {
      integrationId: 'ehr',
      displayName: 'EHR Sandbox',
      serviceType: 'EHR',
      provider: 'FHIR Reference Sandbox',
      enabled: true,
      capabilities: [],
      rolledUpStatus: 'HEALTHY',
      organizations: [],
    },
  ],
});

describe('IntegrationHealthComponent', () => {
  let service: jasmine.SpyObj<IntegrationHealthService>;

  function setup(): IntegrationHealthComponent {
    TestBed.configureTestingModule({
      imports: [IntegrationHealthComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: IntegrationHealthService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(IntegrationHealthComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<IntegrationHealthService>('IntegrationHealthService', [
      'getInventory',
      'getIntegration',
    ]);
  });

  it('renders the inventory grid when loading succeeds', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));

    const cmp = setup();

    expect(service.getInventory).toHaveBeenCalledTimes(1);
    expect(cmp.loading()).toBeFalse();
    expect(cmp.errored()).toBeFalse();
    expect(cmp.integrations().length).toBe(2);
    expect(cmp.countFor('FAILING')).toBe(1);
  });

  it('shows the error panel when the inventory call fails', () => {
    service.getInventory.and.returnValue(throwError(() => new Error('boom')));

    const cmp = setup();

    expect(cmp.loading()).toBeFalse();
    expect(cmp.errored()).toBeTrue();
    expect(cmp.integrations().length).toBe(0);
  });

  it('toggles the expanded integration when the row header is clicked', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));

    const cmp = setup();

    expect(cmp.expandedIntegrationId()).toBeNull();
    cmp.toggle('eligibility');
    expect(cmp.expandedIntegrationId()).toBe('eligibility');
    cmp.toggle('eligibility');
    expect(cmp.expandedIntegrationId()).toBeNull();
  });

  it('countFor returns zero before the summary loads', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));

    const cmp = setup();
    expect(cmp.countFor('HEALTHY')).toBe(1);
    expect(cmp.statusColor('FAILING')).toBe('#ef4444');
  });
});
