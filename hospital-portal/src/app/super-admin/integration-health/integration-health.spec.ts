import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { IntegrationHealthComponent } from './integration-health';
import { IntegrationHealthService } from '../../services/integration-health.service';
import {
  IntegrationHealthSummary,
  IntegrationHistoryBucket,
  IntegrationProbeResult,
} from '../../services/integration-health.model';

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
      provider: 'Insurer connector',
      enabled: true,
      probeSupported: true,
      resyncSupported: true,
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
      probeSupported: false,
      resyncSupported: false,
      capabilities: [],
      rolledUpStatus: 'HEALTHY',
      organizations: [],
    },
  ],
});

const fakeProbeResult = (
  overrides: Partial<IntegrationProbeResult> = {},
): IntegrationProbeResult => ({
  integrationId: 'eligibility',
  ok: true,
  latencyMs: 42,
  message: null,
  ...overrides,
});

const fakeBuckets = (): IntegrationHistoryBucket[] => [
  // The backend's IntegrationHistoryBucketDTO shape (QA D851).
  { bucketStart: '2026-05-02T08:00:00', successCount: 5, failureCount: 0 },
  { bucketStart: '2026-05-02T09:00:00', successCount: 2, failureCount: 8 },
];

describe('IntegrationHealthComponent', () => {
  let service: jasmine.SpyObj<IntegrationHealthService>;

  function setup(): IntegrationHealthComponent {
    return setupFixture().componentInstance;
  }

  function setupFixture() {
    TestBed.configureTestingModule({
      imports: [IntegrationHealthComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: IntegrationHealthService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(IntegrationHealthComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<IntegrationHealthService>('IntegrationHealthService', [
      'getInventory',
      'getIntegration',
      'probe',
      'resync',
      'getHistory',
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
    service.getHistory.and.returnValue(of(fakeBuckets()));

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

  // ── MVP-3b row actions ──────────────────────────────────────────────

  it('probe() flips busy → result and triggers a silent inventory refresh', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.probe.and.returnValue(of(fakeProbeResult()));

    const cmp = setup();
    expect(cmp.rowAction('eligibility').busy).toBeFalse();

    cmp.probe(new Event('click'), 'eligibility');

    expect(service.probe).toHaveBeenCalledWith('eligibility');
    expect(cmp.rowAction('eligibility').busy).toBeFalse();
    expect(cmp.rowAction('eligibility').result?.ok).toBeTrue();
    // Initial load + silent refresh after the probe = 2.
    expect(service.getInventory).toHaveBeenCalledTimes(2);
  });

  it('probe() surfaces an errorKey when the call fails', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.probe.and.returnValue(throwError(() => new Error('network')));

    const cmp = setup();
    cmp.probe(new Event('click'), 'eligibility');

    expect(cmp.rowAction('eligibility').result).toBeNull();
    expect(cmp.rowAction('eligibility').errorKey).toBe('INTEGRATION_HEALTH.PROBE.ERROR');
  });

  it('QA D851: a 202 with no body is an accepted re-sync, not a failure', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.resync.and.returnValue(of(undefined));

    const cmp = setup();
    cmp.resync(new Event('click'), 'eligibility');

    expect(service.resync).toHaveBeenCalledWith('eligibility');
    expect(cmp.rowAction('eligibility').resyncAccepted).toBeTrue();
    expect(cmp.rowAction('eligibility').errorKey).toBeNull();
    // Initial load + silent refresh after the accepted re-sync.
    expect(service.getInventory).toHaveBeenCalledTimes(2);
  });

  it('QA D851: a failed probe shows the server message the backend sends as `message`', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.probe.and.returnValue(of(fakeProbeResult({ ok: false, message: 'partner down' })));

    const cmp = setup();
    cmp.probe(new Event('click'), 'eligibility');

    expect(cmp.rowAction('eligibility').result?.message).toBe('partner down');
  });

  it('QA D851: a failed probe reads in the UI language; the connector text is only a tooltip', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.probe.and.returnValue(
      of(
        fakeProbeResult({ ok: false, message: 'Probe threw IllegalStateException: partner down' }),
      ),
    );

    const fixture = setupFixture();
    fixture.componentInstance.probe(new Event('click'), 'eligibility');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const status = el.querySelector('.action-status.fail') as HTMLElement;
    expect(status.textContent).toContain('INTEGRATION_HEALTH.ACTION.FAIL');
    expect(status.textContent).not.toContain('partner down');
    const detail = el.querySelector('[data-test="probe-detail-eligibility"]') as HTMLElement;
    expect(detail.getAttribute('title')).toBe('Probe threw IllegalStateException: partner down');
  });

  it('QA D851: a refused action shows the server reason, not the generic text', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.resync.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Cette intégration ne peut pas être resynchronisée.' },
          }),
      ),
    );

    const cmp = setup();
    cmp.resync(new Event('click'), 'eligibility');

    expect(cmp.rowAction('eligibility').errorKey).toBe(
      'Cette intégration ne peut pas être resynchronisée.',
    );
  });

  it('QA D851: Test and Re-sync buttons appear only where the row supports them', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));

    const fixture = setupFixture();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('[data-test="probe-eligibility"]')).not.toBeNull();
    expect(el.querySelector('[data-test="resync-eligibility"]')).not.toBeNull();
    expect(el.querySelector('[data-test="probe-ehr"]')).toBeNull();
    expect(el.querySelector('[data-test="resync-ehr"]')).toBeNull();
    expect(el.querySelector('[data-test="no-actions-ehr"]')).not.toBeNull();
  });

  it('resync() failure surfaces the RESYNC error key, not PROBE', () => {
    // Copilot review fix — finishAction now takes the action kind so
    // a re-sync HTTP failure shows the resync-specific i18n key.
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.resync.and.returnValue(throwError(() => new Error('network')));

    const cmp = setup();
    cmp.resync(new Event('click'), 'eligibility');

    expect(cmp.rowAction('eligibility').result).toBeNull();
    expect(cmp.rowAction('eligibility').errorKey).toBe('INTEGRATION_HEALTH.RESYNC.ERROR');
  });

  // ── MVP-3b history drawer ───────────────────────────────────────────

  it('toggle() lazily loads history on first expand', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.getHistory.and.returnValue(of(fakeBuckets()));

    const cmp = setup();
    cmp.toggle('eligibility');

    expect(service.getHistory).toHaveBeenCalledOnceWith('eligibility', 24);
    const state = cmp.history('eligibility');
    expect(state.loading).toBeFalse();
    expect(state.buckets.length).toBe(2);
    // Two points → space-separated x,y pairs in the polyline path.
    expect(state.sparklinePath.split(' ').length).toBe(2);
    // QA D851: plotted from the backend's success/failure counts: no
    // failures sits at the bottom (y=30), 8 of 10 failing at y=6.
    expect(state.sparklinePath).toBe('0.00,30.00 100.00,6.00');
  });

  it('history error surfaces in the drawer state', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.getHistory.and.returnValue(throwError(() => new Error('boom')));

    const cmp = setup();
    cmp.toggle('eligibility');

    expect(cmp.history('eligibility').error).toBeTrue();
    expect(cmp.history('eligibility').buckets.length).toBe(0);
  });

  it('toggle() does not re-fetch history when the row is collapsed and re-expanded', () => {
    service.getInventory.and.returnValue(of(fakeSummary()));
    service.getHistory.and.returnValue(of(fakeBuckets()));

    const cmp = setup();
    cmp.toggle('eligibility');
    cmp.toggle('eligibility'); // collapse
    cmp.toggle('eligibility'); // re-expand

    expect(service.getHistory).toHaveBeenCalledTimes(1);
  });
});
