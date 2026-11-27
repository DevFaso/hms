import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { CostPanelComponent } from './cost-panel.component';
import { ChargebackReport, ChargebackService } from '../../services/chargeback.service';

describe('CostPanelComponent', () => {
  let fixture: ComponentFixture<CostPanelComponent>;
  let svc: jasmine.SpyObj<ChargebackService>;

  const sample: ChargebackReport = {
    from: '2026-10-28',
    to: '2026-11-27',
    currency: 'XOF',
    ratePerAuditEvent: 1,
    totalAuditEvents: 2702,
    totalAmount: 2702,
    rows: [
      {
        hospitalId: 'h-1',
        hospitalName: 'Clinique; "A"',
        auditEventCount: 2598,
        splunkEventCount: 0,
        grafanaSeriesCardinality: 0,
        postgresStorageBytes: 0,
        chargebackAmount: 2598,
        currency: 'XOF',
      },
      {
        hospitalId: 'h-2',
        hospitalName: 'Hôpital B',
        auditEventCount: 104,
        splunkEventCount: 0,
        grafanaSeriesCardinality: 0,
        postgresStorageBytes: 0,
        chargebackAmount: 104,
        currency: 'XOF',
      },
    ],
  };

  const text = (testId: string): string =>
    (fixture.nativeElement.querySelector(`[data-testid="${testId}"]`)?.textContent ?? '').trim();

  beforeEach(async () => {
    svc = jasmine.createSpyObj<ChargebackService>('ChargebackService', ['report']);
    svc.report.and.returnValue(of(sample));

    await TestBed.configureTestingModule({
      imports: [CostPanelComponent, TranslateModule.forRoot()],
      providers: [{ provide: ChargebackService, useValue: svc }],
    }).compileComponents();

    fixture = TestBed.createComponent(CostPanelComponent);
  });

  it('renders the chargeback rows on init', () => {
    fixture.detectChanges();
    expect(svc.report).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-hospital-id="h-1"]')).not.toBeNull();
  });

  it('D4305: shows each amount in F CFA, never in USD', () => {
    fixture.detectChanges();
    const amounts = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="cost-amount"]'),
    ).map((cell) => (cell as HTMLElement).textContent ?? '');
    expect(amounts.length).toBe(2);
    for (const amount of amounts) {
      // The amount and its unit are joined by non-breaking spaces.
      expect(amount).toMatch(/F\sCFA/);
      expect(amount).not.toContain('USD');
    }
    expect(amounts[0].replace(/\D/g, '')).toBe('2598');
  });

  it('D4305: without a rate the amount is absent, not a zero', () => {
    svc.report.and.returnValue(of({ ...sample, ratePerAuditEvent: 0, totalAmount: 0 }));
    fixture.detectChanges();
    expect(text('cost-amount')).toBe('—');
    expect(text('cost-rate')).toBe('COST_PANEL.NO_RATE');
  });

  it('D4307: shows the period the rows cover and fills the date fields with it', async () => {
    fixture.detectChanges();
    expect(text('cost-period')).toBe('COST_PANEL.PERIOD');
    await fixture.whenStable();
    fixture.detectChanges();
    const from = fixture.nativeElement.querySelector(
      '[data-testid="cost-from"]',
    ) as HTMLInputElement;
    const to = fixture.nativeElement.querySelector('[data-testid="cost-to"]') as HTMLInputElement;
    expect(from.value).toBe('2026-10-28');
    expect(to.value).toBe('2026-11-27');
  });

  it('D4307: one total, the one the server computed', () => {
    fixture.detectChanges();
    expect(text('cost-total').replace(/[^\d]/g, '')).toContain('2702');
  });

  it('D4307: a refused period shows the reason the server gave', () => {
    svc.report.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'La période ne peut pas dépasser 92 jours.' },
      })),
    );
    fixture.detectChanges();
    expect(text('cost-error')).toBe('La période ne peut pas dépasser 92 jours.');
  });

  it('D4306: exports the visible rows and the total for the period, safe for a spreadsheet', () => {
    fixture.detectChanges();
    const csv = fixture.componentInstance.toCsv(sample);
    const lines = csv.replace('﻿', '').trim().split('\r\n');
    expect(csv.startsWith('﻿')).toBeTrue();
    expect(lines.length).toBe(4);
    expect(lines[1]).toBe('"Clinique; ""A""";2026-10-28;2026-11-27;2598;2598');
    expect(lines[2]).toBe('Hôpital B;2026-10-28;2026-11-27;104;104');
    expect(lines[3]).toContain(';2702;2702');
    const button = fixture.nativeElement.querySelector(
      '[data-testid="cost-export"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBeFalse();
  });

  it('D4306: a hospital name starting like a formula is exported as text', () => {
    fixture.detectChanges();
    const csv = fixture.componentInstance.toCsv({
      ...sample,
      rows: [{ ...sample.rows[0], hospitalName: '=SUM(A1)' }],
    });
    expect(csv).toContain("'=SUM(A1)");
  });

  it('renders the feature-disabled empty state on 404 from the backing endpoint', () => {
    svc.report.and.returnValue(throwError(() => ({ status: 404 })));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-disabled"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-error"]')).toBeNull();
  });

  it('renders the feature-disabled state on 204, the flag-off answer (D248)', () => {
    svc.report.and.returnValue(of(null));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-disabled"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-error"]')).toBeNull();
  });

  it('renders the generic error state on non-404 failures', () => {
    svc.report.and.returnValue(throwError(() => ({ status: 500 })));
    fixture.detectChanges();
    expect(text('cost-error')).toBe('COST_PANEL.ERROR');
  });

  it('renders the no-data empty state when the service returns no rows', () => {
    svc.report.and.returnValue(of({ ...sample, rows: [], totalAuditEvents: 0, totalAmount: 0 }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-empty"]')).not.toBeNull();
    // Nothing to export: no button at all, rather than one greyed out with no reason.
    expect(fixture.nativeElement.querySelector('[data-testid="cost-export"]')).toBeNull();
  });
});
