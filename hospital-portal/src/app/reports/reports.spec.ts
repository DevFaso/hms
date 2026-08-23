import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ReportsComponent } from './reports';
import { ReportDefinitionResponse, ReportRunResponse, ReportsService } from './reports.service';
import { ToastService } from '../core/toast.service';

function definition(overrides: Partial<ReportDefinitionResponse> = {}): ReportDefinitionResponse {
  return {
    id: 'd1',
    hospitalId: 'h1',
    name: 'Monthly encounters',
    reportType: 'ENCOUNTER_ACTIVITY',
    period: 'MONTHLY',
    recipients: 'admin@example.org',
    active: true,
    createdBy: 'admin1',
    createdAt: '2026-08-22T10:00:00',
    ...overrides,
  };
}

function run(overrides: Partial<ReportRunResponse> = {}): ReportRunResponse {
  return {
    id: 'r1',
    periodToken: '202607',
    status: 'SUCCEEDED',
    rowCount: 31,
    errorMessage: null,
    generatedAt: '2026-08-22T10:05:00',
    createdAt: '2026-08-22T10:05:00',
    ...overrides,
  };
}

describe('ReportsComponent', () => {
  let component: ReportsComponent;
  let fixture: ComponentFixture<ReportsComponent>;
  let mockService: {
    create: jasmine.Spy;
    list: jasmine.Spy;
    runs: jasmine.Spy;
    runNow: jasmine.Spy;
    deactivate: jasmine.Spy;
    reactivate: jasmine.Spy;
  };
  let mockToast: { success: jasmine.Spy; error: jasmine.Spy };

  beforeEach(async () => {
    mockService = {
      create: jasmine.createSpy('create').and.returnValue(of(definition())),
      list: jasmine.createSpy('list').and.returnValue(of([definition()])),
      runs: jasmine.createSpy('runs').and.returnValue(of([run()])),
      runNow: jasmine.createSpy('runNow').and.returnValue(of(run())),
      deactivate: jasmine
        .createSpy('deactivate')
        .and.returnValue(of(definition({ active: false }))),
      reactivate: jasmine.createSpy('reactivate').and.returnValue(of(definition())),
    };
    mockToast = {
      success: jasmine.createSpy('success'),
      error: jasmine.createSpy('error'),
    };

    await TestBed.configureTestingModule({
      imports: [ReportsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ReportsService, useValue: mockService },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReportsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create and load definitions on init', () => {
    expect(component).toBeTruthy();
    expect(mockService.list).toHaveBeenCalled();
    expect(component.definitions()).toHaveSize(1);
  });

  it('refuses to create without required fields', () => {
    component.openCreate();
    component.submitCreate();

    expect(mockService.create).not.toHaveBeenCalled();
    expect(mockToast.error).toHaveBeenCalled();
  });

  it('creates a definition and reloads', () => {
    component.openCreate();
    component.name.set('Weekly appointments');
    component.reportType.set('APPOINTMENT_ACTIVITY');
    component.period.set('WEEKLY');
    component.recipients.set('a@example.org, b@example.org');

    component.submitCreate();

    expect(mockService.create).toHaveBeenCalledWith({
      name: 'Weekly appointments',
      reportType: 'APPOINTMENT_ACTIVITY',
      period: 'WEEKLY',
      recipients: 'a@example.org, b@example.org',
    });
    expect(component.showCreate()).toBeFalse();
    expect(mockToast.success).toHaveBeenCalled();
  });

  it('surfaces the backend refusal verbatim on create failure', () => {
    mockService.create.and.returnValue(
      throwError(() => ({ error: { message: "'nope' is not a valid email address." } })),
    );
    component.openCreate();
    component.name.set('X');
    component.recipients.set('nope');

    component.submitCreate();

    expect(mockToast.error).toHaveBeenCalledWith("'nope' is not a valid email address.");
    expect(component.saving()).toBeFalse();
  });

  it('expands run history', () => {
    component.toggleRuns(definition());

    expect(component.expandedId()).toBe('d1');
    expect(mockService.runs).toHaveBeenCalledWith('d1');
    expect(component.runs()).toHaveSize(1);
  });

  it('runs a report now and reports a failed run as an error toast', () => {
    mockService.runNow.and.returnValue(
      of(run({ status: 'FAILED', errorMessage: 'query exploded' })),
    );

    component.runNow(definition());

    expect(mockToast.error).toHaveBeenCalledWith('query exploded');
    expect(component.actingOnId()).toBeNull();
  });

  it('guards against double submission while a call is in flight', () => {
    component.actingOnId.set('other');
    component.runNow(definition());
    expect(mockService.runNow).not.toHaveBeenCalled();
  });

  it('toggles active state through the matching endpoint', () => {
    component.toggleActive(definition({ active: true }));
    expect(mockService.deactivate).toHaveBeenCalledWith('d1');

    component.toggleActive(definition({ active: false }));
    expect(mockService.reactivate).toHaveBeenCalledWith('d1');
  });
});
