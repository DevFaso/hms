import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ReportsComponent } from './reports';
import { ReportDefinitionResponse, ReportRunResponse, ReportsService } from './reports.service';
import { ToastService } from '../core/toast.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

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

  it('offers the top-diagnoses type and submits it', () => {
    expect(component.reportTypes).toContain('TOP_DIAGNOSES');

    component.openCreate();
    component.name.set('Monthly morbidity');
    component.reportType.set('TOP_DIAGNOSES');
    component.recipients.set('admin@example.org');

    component.submitCreate();

    expect(mockService.create).toHaveBeenCalledWith({
      name: 'Monthly morbidity',
      reportType: 'TOP_DIAGNOSES',
      period: 'MONTHLY',
      recipients: 'admin@example.org',
    });
  });

  it('surfaces the backend refusal verbatim on create failure', () => {
    mockService.create.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'Choisissez un hôpital.' } })),
    );
    component.openCreate();
    component.name.set('X');
    component.recipients.set('a@example.org');

    component.submitCreate();

    expect(mockToast.error).toHaveBeenCalledWith('Choisissez un hôpital.');
    expect(component.saving()).toBeFalse();
  });

  it('QA D855: a malformed recipient is refused on the field before any call', () => {
    component.openCreate();
    component.name.set('Activité');
    component.recipients.set('a@example.org, accueil.clinique@@example');

    component.submitCreate();

    expect(mockService.create).not.toHaveBeenCalled();
    expect(component.recipientsError()).toBe('REPORTS.RECIPIENT_INVALID');
  });

  it('QA D855: the server refusal on the recipients field is shown under it', () => {
    mockService.create.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          message: 'Validation Failed',
          fieldErrors: { recipients: "« x@exemple » n'est pas une adresse e-mail valide." },
        },
      })),
    );
    component.openCreate();
    component.name.set('Activité');
    component.recipients.set('a@example.org');

    component.submitCreate();

    expect(component.recipientsError()).toBe("« x@exemple » n'est pas une adresse e-mail valide.");
    expect(component.saving()).toBeFalse();
  });

  it('expands run history', () => {
    component.toggleRuns(definition());

    expect(component.expandedId()).toBe('d1');
    expect(mockService.runs).toHaveBeenCalledWith('d1');
    expect(component.runs()).toHaveSize(1);
  });

  for (const [field, id] of [
    ['name', 'rep-name'],
    ['reportType', 'rep-type'],
    ['period', 'rep-period'],
    ['recipients', 'rep-recipients'],
  ]) {
    it(`names the server refusal at the ${field} control`, () => {
      const reason = `cdx refused ${field}`;
      mockService.create.and.returnValue(
        throwError(() => ({ status: 400, error: { fieldErrors: { [field]: reason } } })),
      );
      component.openCreate();
      component.name.set('cdx scheduled report');
      component.recipients.set('cdx@example.invalid');
      fixture.detectChanges();

      component.submitCreate();
      fixture.detectChanges();

      const control = fixture.nativeElement.querySelector(`#${id}`) as HTMLElement;
      expect(control.getAttribute('aria-invalid')).toBe('true');
      const description = control.getAttribute('aria-describedby');
      expect(description).toBeTruthy();
      expect(fixture.nativeElement.querySelector(`#${description}`)?.textContent).toContain(reason);
      expect(component.showCreate()).toBeTrue();
      expect(component.saving()).toBeFalse();
    });
  }

  it('shows simultaneous server field refusals and clears them before a corrected retry', () => {
    mockService.create.and.returnValues(
      throwError(() => ({
        status: 400,
        error: { fieldErrors: { name: 'cdx name rejected', period: 'cdx period rejected' } },
      })),
      of(definition()),
    );
    component.openCreate();
    component.name.set('cdx scheduled report');
    component.recipients.set('cdx@example.invalid');
    fixture.detectChanges();
    component.submitCreate();
    fixture.detectChanges();
    for (const id of ['rep-name', 'rep-period']) {
      expect(fixture.nativeElement.querySelector(`#${id}`).getAttribute('aria-invalid')).toBe(
        'true',
      );
    }
    component.submitCreate();
    fixture.detectChanges();
    expect(mockService.create).toHaveBeenCalledTimes(2);
    expect(component.showCreate()).toBeFalse();
  });

  it('removes a corrected field refusal when the next response refuses a different field', () => {
    mockService.create.and.returnValues(
      throwError(() => ({ error: { fieldErrors: { name: 'cdx name refused' } } })),
      throwError(() => ({ error: { fieldErrors: { period: 'cdx period refused' } } })),
    );
    component.openCreate();
    component.name.set('cdx scheduled report');
    component.recipients.set('cdx@example.invalid');
    fixture.detectChanges();
    component.submitCreate();
    fixture.detectChanges();
    component.name.set('cdx corrected report');
    component.submitCreate();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('#rep-name').hasAttribute('aria-invalid'),
    ).toBeFalse();
    expect(fixture.nativeElement.querySelector('#rep-name-invalid-msg')).toBeNull();
    expect(fixture.nativeElement.querySelector('#rep-period').getAttribute('aria-invalid')).toBe(
      'true',
    );
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

  it('toggles active state through the matching endpoint', async () => {
    const ask = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    await component.toggleActive(definition({ active: true }));
    expect(mockService.deactivate).toHaveBeenCalledWith('d1');
    expect(ask).toHaveBeenCalledTimes(1);

    // QA CX-010: turning a report back on ends nothing, so it asks nothing.
    await component.toggleActive(definition({ active: false }));
    expect(mockService.reactivate).toHaveBeenCalledWith('d1');
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('QA CX-010: a no to deactivating sends nothing', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);
    await component.toggleActive(definition({ active: true }));
    expect(mockService.deactivate).not.toHaveBeenCalled();
  });
});
