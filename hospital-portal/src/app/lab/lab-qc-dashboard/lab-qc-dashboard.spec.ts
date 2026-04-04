import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LabQcDashboardComponent } from './lab-qc-dashboard';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import {
  LabService,
  LabQcSummary,
  LabValidationSummary,
  LabQcEvent,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';

describe('LabQcDashboardComponent', () => {
  let component: LabQcDashboardComponent;
  let fixture: ComponentFixture<LabQcDashboardComponent>;

  const qcSummaryData: LabQcSummary[] = [
    {
      testDefinitionId: 'def-1',
      testName: 'CBC',
      totalEvents: 50,
      failedEvents: 2,
      lastEventDate: '2026-04-01T10:00:00',
    },
    {
      testDefinitionId: 'def-2',
      testName: 'BMP',
      totalEvents: 30,
      failedEvents: 0,
      lastEventDate: '2026-03-28T09:00:00',
    },
  ];

  const validationData: LabValidationSummary[] = [
    {
      testDefinitionId: 'def-1',
      testName: 'CBC',
      testCode: 'CBC',
      totalStudies: 10,
      passedStudies: 9,
      failedStudies: 1,
      passRate: 90,
      lastStudyDate: '2026-04-15',
    },
  ];

  const mockLabService = {
    getQcSummary: jasmine.createSpy('getQcSummary').and.returnValue(of(qcSummaryData)),
    getValidationSummary: jasmine
      .createSpy('getValidationSummary')
      .and.returnValue(of(validationData)),
    getQcEventsByDefinition: jasmine.createSpy('getQcEventsByDefinition').and.returnValue(of([])),
  };

  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    mockLabService.getQcSummary.and.returnValue(of(qcSummaryData));
    mockLabService.getValidationSummary.and.returnValue(of(validationData));
    mockLabService.getQcEventsByDefinition.and.returnValue(of([]));
    mockToastService.error.calls.reset();

    await TestBed.configureTestingModule({
      imports: [LabQcDashboardComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LabService, useValue: mockLabService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LabQcDashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load QC and validation data on init', () => {
    expect(component.loading()).toBeFalse();
    expect(component.qcSummary()).toEqual(qcSummaryData);
    expect(component.validationSummary()).toEqual(validationData);
  });

  it('should compute totalQcEvents', () => {
    expect(component.totalQcEvents()).toBe(80); // 50 + 30
  });

  it('should compute totalQcFailures', () => {
    expect(component.totalQcFailures()).toBe(2); // 2 + 0
  });

  it('should compute totalValidationStudies', () => {
    expect(component.totalValidationStudies()).toBe(10);
  });

  it('should compute overallPassRate', () => {
    expect(component.overallPassRate()).toBe(90); // 9/10 * 100
  });

  it('should show toast on load error', () => {
    mockLabService.getQcSummary.and.returnValue(throwError(() => new Error('fail')));
    const newFixture = TestBed.createComponent(LabQcDashboardComponent);
    newFixture.detectChanges();

    expect(mockToastService.error).toHaveBeenCalledWith('Failed to load QC dashboard data.');
  });

  // ── drill-down ──────────────────────────────────────────────────────────

  it('should expand chart on toggleChart', () => {
    component.toggleChart('def-1');
    expect(component.expandedDefinitionId()).toBe('def-1');
    expect(mockLabService.getQcEventsByDefinition).toHaveBeenCalledWith('def-1');
  });

  it('should collapse chart when same definition toggled', () => {
    component.toggleChart('def-1');
    component.toggleChart('def-1');
    expect(component.expandedDefinitionId()).toBeNull();
    expect(component.qcEvents()).toEqual([]);
  });

  it('should switch to different definition on toggle', () => {
    component.toggleChart('def-1');
    component.toggleChart('def-2');
    expect(component.expandedDefinitionId()).toBe('def-2');
  });

  it('should compute qcChartGroups as empty when no events', () => {
    component.qcEvents.set([]);
    expect(component.qcChartGroups()).toEqual([]);
  });

  it('should compute qcChartGroups with insufficient data', () => {
    const events: LabQcEvent[] = [
      {
        id: '1',
        qcLevel: 'LOW_CONTROL',
        measuredValue: 100,
        recordedAt: '2026-04-01T10:00:00',
      } as LabQcEvent,
      {
        id: '2',
        qcLevel: 'LOW_CONTROL',
        measuredValue: 102,
        recordedAt: '2026-04-02T10:00:00',
      } as LabQcEvent,
    ];
    component.qcEvents.set(events);
    const groups = component.qcChartGroups();
    expect(groups.length).toBe(1);
    expect(groups[0].level).toBe('LOW_CONTROL');
    expect(groups[0].insufficient).toBeTrue();
  });

  it('should compute full chart group with 3+ events', () => {
    const events: LabQcEvent[] = [
      {
        id: '1',
        qcLevel: 'HIGH_CONTROL',
        measuredValue: 100,
        recordedAt: '2026-04-01T10:00:00',
      } as LabQcEvent,
      {
        id: '2',
        qcLevel: 'HIGH_CONTROL',
        measuredValue: 102,
        recordedAt: '2026-04-02T10:00:00',
      } as LabQcEvent,
      {
        id: '3',
        qcLevel: 'HIGH_CONTROL',
        measuredValue: 98,
        recordedAt: '2026-04-03T10:00:00',
      } as LabQcEvent,
    ];
    component.qcEvents.set(events);
    const groups = component.qcChartGroups();
    expect(groups.length).toBe(1);
    expect(groups[0].insufficient).toBeFalse();
    expect(groups[0].points.length).toBe(3);
    expect(groups[0].mean).toBe(100);
    expect(groups[0].sd).toBeGreaterThan(0);
  });

  it('should show toast on chart load error', () => {
    mockLabService.getQcEventsByDefinition.and.returnValue(
      throwError(() => new Error('chart fail')),
    );
    component.toggleChart('def-1');
    expect(mockToastService.error).toHaveBeenCalledWith('Failed to load QC events for chart.');
  });
});
