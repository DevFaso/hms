import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { CostPanelComponent } from './cost-panel.component';
import { ChargebackRow, ChargebackService } from '../../services/chargeback.service';

describe('CostPanelComponent', () => {
  let fixture: ComponentFixture<CostPanelComponent>;
  let svc: jasmine.SpyObj<ChargebackService>;

  const sample: ChargebackRow = {
    hospitalId: 'h-1',
    hospitalName: 'Aspen Memorial',
    auditEventCount: 1234,
    splunkEventCount: 0,
    grafanaSeriesCardinality: 0,
    postgresStorageBytes: 0,
    chargebackAmount: 12.34,
    currency: 'USD',
  };

  beforeEach(async () => {
    svc = jasmine.createSpyObj<ChargebackService>('ChargebackService', ['perTenant']);
    svc.perTenant.and.returnValue(of([sample]));

    await TestBed.configureTestingModule({
      imports: [CostPanelComponent, TranslateModule.forRoot()],
      providers: [{ provide: ChargebackService, useValue: svc }],
    }).compileComponents();

    fixture = TestBed.createComponent(CostPanelComponent);
  });

  it('renders the chargeback rows on init', () => {
    fixture.detectChanges();
    expect(svc.perTenant).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-hospital-id="h-1"]')).not.toBeNull();
  });

  it('renders the feature-disabled empty state on 404 from the backing endpoint', () => {
    svc.perTenant.and.returnValue(throwError(() => ({ status: 404 })));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-disabled"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-error"]')).toBeNull();
  });

  it('renders the generic error state on non-404 failures', () => {
    svc.perTenant.and.returnValue(throwError(() => ({ status: 500 })));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-error"]')).not.toBeNull();
  });

  it('renders the no-data empty state when the service returns []', () => {
    svc.perTenant.and.returnValue(of([]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="cost-empty"]')).not.toBeNull();
  });
});
