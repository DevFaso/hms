import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import fr from '../../assets/i18n/fr.json';
import { of, Subject, throwError } from 'rxjs';

import { ReportsComponent, closedPeriodTokens } from './reports';
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

  it('renders report history periods in the current language and keeps the original token', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    expect(component.periodLabel('202610', 'MONTHLY')).toBe('octobre 2026');
    expect(component.periodLabel('2026-W01', 'WEEKLY')).toBe('Semaine 1, 2026');
    expect(component.periodLabel('2026-10-08', 'DAILY')).toBe('8 oct. 2026');
    expect(component.periodLabel('202613', 'MONTHLY')).toBe('202613');
    expect(component.periodLabel('2026-02-30', 'DAILY')).toBe('2026-02-30');
    expect(component.periodLabel('2026-W00', 'WEEKLY')).toBe('2026-W00');
    expect(component.periodLabel('2025-W53', 'WEEKLY')).toBe('2025-W53');
    expect(component.periodLabel('2026-W53', 'WEEKLY')).toBe('Semaine 53, 2026');
    component.toggleRuns(definition());
    fixture.detectChanges();
    const cell = fixture.nativeElement.querySelector('.runs-table tbody tr td') as HTMLElement;
    expect(cell.textContent?.trim()).toBe('juillet 2026');
    expect(cell.title).toBe('202607');
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
    const retry = new Subject<ReportDefinitionResponse>();
    mockService.create.and.returnValues(
      throwError(() => ({
        status: 400,
        error: { fieldErrors: { name: 'cdx name rejected', period: 'cdx period rejected' } },
      })),
      retry,
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
    expect(component.showCreate()).toBeTrue();
    for (const id of ['rep-name', 'rep-period']) {
      expect(
        fixture.nativeElement.querySelector(`#${id}`).hasAttribute('aria-invalid'),
      ).toBeFalse();
    }
    retry.next(definition());
    retry.complete();
    fixture.detectChanges();
    expect(component.showCreate()).toBeFalse();
  });

  it('shows an unmapped refusal alongside a mapped field error and focuses the mapped field', () => {
    document.body.appendChild(fixture.nativeElement);
    mockService.create.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          fieldErrors: { name: 'cdx name refused', schedule: 'cdx schedule refused' },
        },
      })),
    );
    component.openCreate();
    component.name.set('cdx report');
    component.recipients.set('cdx@example.invalid');
    fixture.detectChanges();
    component.submitCreate();
    fixture.detectChanges();
    const name = fixture.nativeElement.querySelector('#rep-name');
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(name);
    expect(mockToast.error).toHaveBeenCalledWith('cdx schedule refused');
  });

  it('falls back to the refusal message when the recipients reason is blank', () => {
    mockService.create.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          message: 'cdx request refused',
          fieldErrors: { recipients: '   ' },
        },
      })),
    );
    component.openCreate();
    component.name.set('cdx report');
    component.recipients.set('cdx@example.invalid');
    fixture.detectChanges();
    component.submitCreate();
    fixture.detectChanges();
    expect(component.recipientsError()).toBeNull();
    expect(mockToast.error).toHaveBeenCalledWith('cdx request refused');
    expect(
      fixture.nativeElement.querySelector('#rep-recipients').hasAttribute('aria-invalid'),
    ).toBeFalse();
  });

  it('clears the previous recipients error when a later response refuses only the name', () => {
    mockService.create.and.returnValues(
      throwError(() => ({
        status: 400,
        error: { fieldErrors: { recipients: 'cdx recipients refused' } },
      })),
      throwError(() => ({ status: 400, error: { fieldErrors: { name: 'cdx name refused' } } })),
    );
    component.openCreate();
    component.name.set('cdx report');
    component.recipients.set('cdx@example.invalid');
    fixture.detectChanges();
    component.submitCreate();
    fixture.detectChanges();
    expect(component.recipientsError()).toBe('cdx recipients refused');
    component.submitCreate();
    fixture.detectChanges();
    expect(component.recipientsError()).toBeNull();
    expect(
      fixture.nativeElement.querySelector('#rep-recipients').hasAttribute('aria-invalid'),
    ).toBeFalse();
    expect(fixture.nativeElement.querySelector('#rep-name').getAttribute('aria-invalid')).toBe(
      'true',
    );
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

  describe('QA D4023: the run asks for a period, says what it did, and fits at 390 px', () => {
    it('offers the closed periods in the server format, the server default first', () => {
      const today = new Date(2026, 10, 27); // 27 November 2026, a Friday of ISO week 48
      expect(closedPeriodTokens('MONTHLY', today, 3)).toEqual(['202610', '202609', '202608']);
      expect(closedPeriodTokens('DAILY', today, 2)).toEqual(['2026-11-26', '2026-11-25']);
      expect(closedPeriodTokens('WEEKLY', today, 2)).toEqual(['2026-W47', '2026-W46']);
      // Across a year: 5 January 2026 is in week 2; the week before is 2026-W01, then 2025-W52.
      expect(closedPeriodTokens('WEEKLY', new Date(2026, 0, 12), 3)).toEqual([
        '2026-W02',
        '2026-W01',
        '2025-W52',
      ]);
      expect(closedPeriodTokens('MONTHLY', new Date(2026, 0, 15), 1)).toEqual(['202512']);
    });

    it('« Exécuter » opens the period choice, marks the periods already sent and runs the one chosen', () => {
      const tokens = component.runChoices(definition());
      mockService.runs.and.returnValue(of([run({ periodToken: tokens[0] })]));
      (
        fixture.nativeElement.querySelector('[data-testid="report-run"]') as HTMLButtonElement
      ).click();
      fixture.detectChanges();

      expect(mockService.runNow).not.toHaveBeenCalled();
      const select = fixture.nativeElement.querySelector('#rep-run-period') as HTMLSelectElement;
      expect(select).not.toBeNull();
      const first = Array.from(select.options).find((o) => o.value === tokens[0])!;
      expect(first.disabled).toBeTrue();
      // The newest period not sent yet is the one proposed.
      expect(component.runToken()).toBe(tokens[1]);

      component.runToken.set(tokens[2]);
      component.confirmRun();

      expect(mockService.runNow).toHaveBeenCalledWith('d1', tokens[2]);
    });

    it('a period already sent is not sent again', () => {
      const tokens = component.runChoices(definition());
      mockService.runs.and.returnValue(of([run({ periodToken: tokens[0] })]));
      component.openRun(definition());
      component.runToken.set(tokens[0]);
      component.confirmRun();
      expect(mockService.runNow).not.toHaveBeenCalled();
    });

    it('a successful run is said on the page with its period and its rows, and the history shows it', () => {
      mockService.runNow.and.returnValue(of(run({ periodToken: '202610', rowCount: 1 })));
      component.runNow(definition(), '202610');
      fixture.detectChanges();

      const notice = fixture.nativeElement.querySelector(
        '[data-testid="report-run-notice"]',
      ) as HTMLElement;
      expect(notice).not.toBeNull();
      expect(notice.getAttribute('role')).toBe('status');
      expect(component.runNotice()).toEqual(
        jasmine.objectContaining({ name: 'Monthly encounters', rows: 1 }),
      );
      expect(notice.textContent).toContain('REPORTS.RUN_NOTICE_ONE');
      expect(component.expandedId()).toBe('d1');
    });

    it('the row buttons are house buttons in a wrapping group, the cell stays a table cell', () => {
      const cell = fixture.nativeElement.querySelector('td.actions-cell') as HTMLElement;
      expect(getComputedStyle(cell).display).toBe('table-cell');
      const buttons = Array.from(cell.querySelectorAll('button'));
      expect(buttons.length).toBe(3);
      for (const button of buttons) {
        expect(
          button.classList.contains('btn-primary') || button.classList.contains('btn-secondary'),
        )
          .withContext(button.textContent?.trim() ?? '')
          .toBeTrue();
        expect(button.classList.contains('btn-ghost')).toBeFalse();
      }
      expect(getComputedStyle(cell.querySelector('.row-actions')!).flexWrap).toBe('wrap');
    });

    it('the history column says what the count is', () => {
      expect(fr.REPORTS.ROWS).toBe('Lignes de données');
    });
  });
});
