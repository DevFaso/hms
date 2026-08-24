import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MorbidityComponent } from './morbidity';
import { MorbidityDashboard, MorbidityService } from './morbidity.service';
import { ToastService } from '../core/toast.service';

function hospitalScoped(): MorbidityDashboard {
  return {
    month: '2026-08',
    scope: 'HOSPITAL',
    hospitalName: 'Hospital A',
    overall: [
      { code: 'B54', display: 'Malaria, unspecified', count: 210 },
      { code: 'I10', display: 'Essential hypertension', count: 88 },
    ],
    byHospital: [],
  };
}

function networkScoped(): MorbidityDashboard {
  return {
    month: '2026-08',
    scope: 'NETWORK',
    hospitalName: null,
    overall: [{ code: 'B54', display: 'Malaria, unspecified', count: 412 }],
    byHospital: [
      {
        hospitalId: 'h1',
        hospitalName: 'Hospital A',
        top: [{ code: 'B54', display: 'Malaria, unspecified', count: 210 }],
        totalRecorded: 298,
      },
      {
        hospitalId: 'h2',
        hospitalName: 'Hospital B',
        top: [{ code: 'A00', display: 'Cholera', count: 121 }],
        totalRecorded: 121,
      },
    ],
  };
}

describe('MorbidityComponent', () => {
  let component: MorbidityComponent;
  let fixture: ComponentFixture<MorbidityComponent>;
  let mockService: { topDiagnoses: jasmine.Spy };
  let mockToast: { success: jasmine.Spy; error: jasmine.Spy };

  async function build(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [MorbidityComponent, TranslateModule.forRoot()],
      providers: [
        { provide: MorbidityService, useValue: mockService },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MorbidityComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => {
    mockService = {
      topDiagnoses: jasmine.createSpy('topDiagnoses').and.returnValue(of(hospitalScoped())),
    };
    mockToast = {
      success: jasmine.createSpy('success'),
      error: jasmine.createSpy('error'),
    };
  });

  it('loads the current month on init', async () => {
    await build();

    expect(component).toBeTruthy();
    const now = new Date();
    const expected = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    expect(mockService.topDiagnoses).toHaveBeenCalledWith(expected);
    expect(component.month()).toBe(expected);
  });

  it('renders a hospital-scoped response without a breakdown', async () => {
    await build();

    expect(component.isNetwork()).toBeFalse();
    expect(component.hasBreakdown()).toBeFalse();
    expect(component.data()?.hospitalName).toBe('Hospital A');
    expect(component.totalShown()).toBe(298);
  });

  it('renders the per-hospital breakdown for a network-scoped response', async () => {
    mockService.topDiagnoses.and.returnValue(of(networkScoped()));
    await build();

    expect(component.isNetwork()).toBeTrue();
    expect(component.hasBreakdown()).toBeTrue();
    expect(component.data()?.byHospital).toHaveSize(2);
    // The card subtitle names each hospital's own leading diagnosis —
    // the whole point of the comparison view.
    expect(component.leadDiagnosis(0)).toBe('Malaria, unspecified');
    expect(component.leadDiagnosis(1)).toBe('Cholera');
  });

  it('shows an explicit error state rather than an empty chart on failure', async () => {
    mockService.topDiagnoses.and.returnValue(throwError(() => new Error('boom')));
    await build();

    expect(component.failed()).toBeTrue();
    expect(component.data()).toBeNull();
    expect(component.loading()).toBeFalse();
    expect(mockToast.error).toHaveBeenCalled();
  });

  it('steps back a month and reloads', async () => {
    await build();
    component.month.set('2026-08');
    mockService.topDiagnoses.calls.reset();

    component.previousMonth();

    expect(component.month()).toBe('2026-07');
    expect(mockService.topDiagnoses).toHaveBeenCalledWith('2026-07');
  });

  it('rolls the year back correctly across January', async () => {
    await build();
    component.month.set('2026-01');
    mockService.topDiagnoses.calls.reset();

    component.previousMonth();

    expect(component.month()).toBe('2025-12');
  });

  it('refuses to step past the current month', async () => {
    await build();
    mockService.topDiagnoses.calls.reset();

    expect(component.atLatestMonth()).toBeTrue();
    component.nextMonth();

    expect(mockService.topDiagnoses).not.toHaveBeenCalled();
  });

  it('rolls the year forward correctly across December', async () => {
    await build();
    component.month.set('2025-12');
    mockService.topDiagnoses.calls.reset();

    component.nextMonth();

    expect(component.month()).toBe('2026-01');
  });
});
