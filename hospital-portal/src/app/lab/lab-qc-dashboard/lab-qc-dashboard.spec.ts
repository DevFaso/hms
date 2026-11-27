import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LabQcDashboardComponent } from './lab-qc-dashboard';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
import {
  LabService,
  LabQcSummary,
  LabValidationSummary,
  LabQcEvent,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { of, throwError } from 'rxjs';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

describe('LabQcDashboardComponent', () => {
  let component: LabQcDashboardComponent;
  let fixture: ComponentFixture<LabQcDashboardComponent>;

  const qcSummaryData: LabQcSummary[] = [
    {
      testDefinitionId: 'def-1',
      testName: 'CBC',
      totalEvents: 50,
      passedEvents: 48,
      failedEvents: 2,
      passRate: 96,
      lastEventDate: '2026-04-01T10:00:00',
    },
    {
      testDefinitionId: 'def-2',
      testName: 'BMP',
      totalEvents: 30,
      passedEvents: 30,
      failedEvents: 0,
      passRate: 100,
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

  /** r63 D2304: the reader's active roles, changed per test. */
  let activeRoles: string[] = ['ROLE_LAB_DIRECTOR'];
  const mockRoleContext = {
    hasAnyActiveRole: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
    hasAnyActiveRoleExactly: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
    effectiveHospitalIdForRequest: () => 'h-1',
  };

  beforeEach(async () => {
    mockLabService.getQcSummary.and.returnValue(of(qcSummaryData));
    mockLabService.getValidationSummary.and.returnValue(of(validationData));
    mockLabService.getQcEventsByDefinition.and.returnValue(of([]));
    mockToastService.error.calls.reset();
    activeRoles = ['ROLE_LAB_DIRECTOR'];

    await TestBed.configureTestingModule({
      imports: [LabQcDashboardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: LabService, useValue: mockLabService },
        { provide: ToastService, useValue: mockToastService },
        { provide: RoleContextService, useValue: mockRoleContext },
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

  it('shows no rate when there are no validation studies', () => {
    component.validationSummary.set([]);
    fixture.detectChanges();
    const rate: HTMLElement = fixture.nativeElement.querySelector(
      '[data-testid="validation-pass-rate"]',
    );
    expect(rate.textContent?.trim()).toBe('—');
  });

  it('should show toast on load error', () => {
    mockLabService.getQcSummary.and.returnValue(throwError(() => new Error('fail')));
    const newFixture = TestBed.createComponent(LabQcDashboardComponent);
    newFixture.detectChanges();

    expect(mockToastService.error).toHaveBeenCalledWith('LAB_QC.TOAST.LOAD_FAILED');
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
    expect(mockToastService.error).toHaveBeenCalledWith('LAB_QC.TOAST.CHART_LOAD_FAILED');
  });
  // ── r63 D2304: recording QC runs and the director's targets ──

  describe('QC entry (r63 D2304)', () => {
    const target = {
      id: 't-1',
      testDefinitionId: 'def-1',
      testDefinitionName: 'Kaliemie',
      qcLevel: 'LOW_CONTROL' as const,
      targetMean: 5,
      targetSd: 0.2,
      lotNumber: 'L-1',
      approvedByDisplay: 'Dir',
      approvedAt: '2026-11-19T08:00:00',
    };
    let lab: {
      listTestDefinitions: jasmine.Spy;
      getQcTargets: jasmine.Spy;
      recordQcEvent: jasmine.Spy;
      saveQcTarget: jasmine.Spy;
    };

    beforeEach(() => {
      lab = mockLabService as unknown as typeof lab;
      lab.listTestDefinitions = jasmine
        .createSpy('listTestDefinitions')
        .and.returnValue(
          of([{ id: 'def-1', testName: 'Kaliemie', testCode: 'K', isActive: true }]),
        );
      lab.getQcTargets = jasmine.createSpy('getQcTargets').and.returnValue(of([target]));
      lab.recordQcEvent = jasmine.createSpy('recordQcEvent').and.returnValue(of({}));
      lab.saveQcTarget = jasmine.createSpy('saveQcTarget').and.returnValue(of(target));
    });

    it('lets the technician in: no validation-study read, the run form, no target editing', () => {
      activeRoles = ['ROLE_LAB_TECHNICIAN'];
      mockLabService.getValidationSummary.calls.reset();
      const techFixture = TestBed.createComponent(LabQcDashboardComponent);
      techFixture.detectChanges();
      const tech = techFixture.componentInstance;
      expect(mockLabService.getValidationSummary).not.toHaveBeenCalled();
      expect(tech.canRecord()).toBeTrue();
      expect(tech.canSetTargets()).toBeFalse();
      expect(
        techFixture.nativeElement.querySelector('[data-testid="qc-record-open"]'),
      ).not.toBeNull();
    });

    it('records a rejected run with its lot, sending the target mean as the expected value', () => {
      component.openRecordForm('def-1');
      component.qcMeasured.set('5,7');
      component.qcLot.set(' LOT-9 ');
      component.qcAccepted.set(false);
      expect(component.recordIncomplete()).toBeFalse();
      component.recordRun();
      expect(lab.recordQcEvent).toHaveBeenCalledWith(
        jasmine.objectContaining({
          hospitalId: 'h-1',
          testDefinitionId: 'def-1',
          qcLevel: 'LOW_CONTROL',
          measuredValue: 5.7,
          lotNumber: 'LOT-9',
          accepted: false,
        }),
      );
      expect(lab.recordQcEvent.calls.mostRecent().args[0].expectedValue).toBe(5);
    });

    it('needs an expected value and a decision when no target is set', () => {
      lab.getQcTargets.and.returnValue(of([]));
      component.openRecordForm('def-1');
      component.qcMeasured.set('5');
      expect(component.recordIncomplete()).toBeTrue();
      component.qcExpected.set('5');
      expect(component.recordIncomplete()).toBeTrue();
      component.qcAccepted.set(true);
      expect(component.recordIncomplete()).toBeFalse();
      expect(component.liveFlag()).toBeNull();
    });

    it('shows the value against the target in SDs, only when a target is set', () => {
      component.openRecordForm('def-1');
      component.qcMeasured.set('5.5');
      expect(component.liveFlag()).toBe('OUTSIDE_2SD');
      component.qcMeasured.set('5.7');
      expect(component.liveFlag()).toBe('OUTSIDE_3SD');
      component.qcMeasured.set('5.1');
      expect(component.liveFlag()).toBe('WITHIN_2SD');
    });

    it('D2616: the target readout uses French decimals', () => {
      const previous = localStorage.getItem('lang');
      try {
        localStorage.setItem('lang', 'fr');
        resetStoredLangForTests();
        const translate = TestBed.inject(TranslateService);
        translate.setTranslation(
          'fr',
          { LAB_QC: { TARGET: { CURRENT: '{{mean}} / {{sd}}' } } },
          true,
        );
        translate.use('fr');
        component.openRecordForm('def-1');
        fixture.detectChanges();
        expect(
          fixture.nativeElement.querySelector('[data-testid="qc-target-readout"]').textContent,
        ).toContain('5 / 0,2');
      } finally {
        if (previous === null) localStorage.removeItem('lang');
        else localStorage.setItem('lang', previous);
        resetStoredLangForTests();
      }
    });

    it('lets the lab director set and approve a target', () => {
      component.openRecordForm('def-1');
      expect(component.canSetTargets()).toBeTrue();
      component.targetMean.set('4.8');
      component.targetSd.set('0.25');
      component.saveTarget();
      expect(lab.saveQcTarget).toHaveBeenCalledWith(
        jasmine.objectContaining({ testDefinitionId: 'def-1', targetMean: 4.8, targetSd: 0.25 }),
      );
    });

    it('QA D1124: an empty run is refused field by field, never sent', () => {
      component.openRecordForm();
      fixture.detectChanges();
      const marked = expectEmptySubmitRefusal(fixture, {
        button: '[data-testid="qc-record-submit"]',
        sent: lab.recordQcEvent,
      });
      expect(marked).toContain('qc-test');
    });

    it('QA D1124: an empty target is refused field by field, never sent', () => {
      lab.getQcTargets.and.returnValue(of([]));
      component.openRecordForm('def-1');
      fixture.detectChanges();
      const marked = expectEmptySubmitRefusal(fixture, {
        button: '[data-testid="qc-target-save"]',
        sent: lab.saveQcTarget,
      });
      expect(marked).toEqual(jasmine.arrayContaining(['qc-target-mean', 'qc-target-sd']));
    });

    it('fits a phone screen (390 px) without a sideways scroll', () => {
      component.openRecordForm('def-1');
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      host.style.display = 'block';
      host.style.width = '390px';
      const form = host.querySelector<HTMLElement>('[data-testid="qc-record-form"]')!;
      expect(form).not.toBeNull();
      expect(form.scrollWidth).toBeLessThanOrEqual(form.clientWidth + 1);
    });
  });
});
