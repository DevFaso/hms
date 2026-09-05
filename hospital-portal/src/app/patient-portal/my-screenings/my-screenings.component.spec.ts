import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';
import { MyScreeningsComponent } from './my-screenings.component';
import {
  ProInstrumentView,
  ProScreeningService,
  ProSelfReport,
  ProSelfReportEntry,
} from '../../services/pro-screening.service';
import { ToastService } from '../../core/toast.service';

/**
 * Tier 2 item 47. Two things this page must never do: tell a patient "no
 * screening is open" when the request merely failed, and show her a score.
 * Both are asserted here rather than left to the template's good manners.
 *
 * The instrument is a made-up two-item fixture — never EPDS text.
 */
describe('MyScreeningsComponent', () => {
  let component: MyScreeningsComponent;
  let fixture: ComponentFixture<MyScreeningsComponent>;

  const instrument: ProInstrumentView = {
    code: 'TEST',
    name: 'Test instrument',
    language: 'en',
    availableLanguages: ['en', 'fr'],
    maxScore: 4,
    items: [
      {
        itemNo: 1,
        prompt: 'First question',
        options: [
          { optionNo: 0, label: 'Never' },
          { optionNo: 2, label: 'Often' },
        ],
      },
      {
        itemNo: 2,
        prompt: 'Second question',
        options: [
          { optionNo: 0, label: 'No' },
          { optionNo: 2, label: 'Yes' },
        ],
      },
    ],
  };

  const entry = (over: Partial<ProSelfReportEntry> = {}): ProSelfReportEntry => ({
    id: 'r1',
    instrumentCode: 'TEST',
    instrumentName: 'Test instrument',
    administeredAt: '2026-09-01T10:00:00',
    followUpPlanned: false,
    careTeamAlerted: false,
    ...over,
  });

  let report: () => Observable<ProSelfReport>;
  let instrumentResult: () => Observable<ProInstrumentView>;
  let submitResult: () => Observable<ProSelfReportEntry>;

  const service = {
    myScreenings: jasmine.createSpy('myScreenings').and.callFake(() => report()),
    myInstrument: jasmine.createSpy('myInstrument').and.callFake(() => instrumentResult()),
    submitMine: jasmine.createSpy('submitMine').and.callFake(() => submitResult()),
  };

  const toast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
    warning: jasmine.createSpy('warning'),
  };

  beforeEach(async () => {
    report = () => of({ available: [], history: [] });
    instrumentResult = () => of(instrument);
    submitResult = () => of(entry({ id: 'new', followUpPlanned: true }));
    service.myScreenings.calls.reset();
    service.myInstrument.calls.reset();
    service.submitMine.calls.reset();
    toast.success.calls.reset();
    toast.error.calls.reset();
    toast.warning.calls.reset();

    await TestBed.configureTestingModule({
      imports: [MyScreeningsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ProScreeningService, useValue: service },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyScreeningsComponent);
    component = fixture.componentInstance;
  });

  const text = (): string => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('shows the empty state when nothing is open', () => {
    fixture.detectChanges();
    expect(component.loading()).toBeFalse();
    expect(component.failed()).toBeFalse();
    expect(text()).toContain('PRO.MY.NONE_OPEN_TITLE');
  });

  it('distinguishes a failed load from "nothing open" and offers a retry', () => {
    report = () => throwError(() => new Error('boom'));
    fixture.detectChanges();
    expect(component.failed()).toBeTrue();
    expect(text()).toContain('PRO.MY.LOAD_FAILED_TITLE');
    expect(text()).not.toContain('PRO.MY.NONE_OPEN_TITLE');

    report = () => of({ available: [], history: [] });
    component.load();
    fixture.detectChanges();
    expect(component.failed()).toBeFalse();
    expect(service.myScreenings).toHaveBeenCalledTimes(2);
  });

  it('lists what is open and loads the instrument on start', () => {
    report = () =>
      of({
        available: [{ code: 'TEST', name: 'Test instrument', languages: ['en'] }],
        history: [],
      });
    fixture.detectChanges();
    expect(text()).toContain('PRO.MY.START');

    component.start({ code: 'TEST', name: 'Test instrument', languages: ['en'] });
    fixture.detectChanges();
    expect(service.myInstrument).toHaveBeenCalledWith('TEST', undefined);
    expect(component.instrument()?.code).toBe('TEST');
    expect(component.language).toBe('en');
    expect(fixture.nativeElement.querySelectorAll('fieldset.pro-item').length).toBe(2);
  });

  it('a failed instrument load is reported, and the next attempt still goes out', () => {
    instrumentResult = () => throwError(() => new Error('boom'));
    fixture.detectChanges();
    component.start({ code: 'TEST', name: 'Test instrument', languages: ['en', 'fr'] });
    expect(component.instrumentFailed()).toBeTrue();
    expect(component.instrumentLoading()).toBeFalse();
    expect(component.instrument()).toBeNull();

    instrumentResult = () => of({ ...instrument, language: 'fr' });
    component.changeLanguage('fr');
    expect(component.instrumentFailed()).toBeFalse();
    expect(component.instrument()?.language).toBe('fr');
  });

  it('a slow earlier language never overwrites the one chosen last', () => {
    const slowEnglish = new Subject<ProInstrumentView>();
    instrumentResult = () => slowEnglish;
    fixture.detectChanges();
    component.start({ code: 'TEST', name: 'Test instrument', languages: ['en', 'fr'] });
    instrumentResult = () => of({ ...instrument, language: 'fr' });
    component.changeLanguage('fr');
    expect(component.language).toBe('fr');

    slowEnglish.next({ ...instrument, language: 'en' });
    slowEnglish.complete();
    expect(component.language).toBe('fr');
    expect(component.instrument()?.language).toBe('fr');
  });

  it('a late response for a cancelled form is dropped', () => {
    const slow = new Subject<ProInstrumentView>();
    instrumentResult = () => slow;
    fixture.detectChanges();
    component.start({ code: 'TEST', name: 'Test instrument', languages: ['en'] });
    component.cancel();
    expect(component.instrumentLoading()).toBeFalse();

    slow.next(instrument);
    slow.complete();
    expect(component.instrument()).toBeNull();
    expect(component.active()).toBeNull();
  });

  it('refuses to submit until every item is answered', () => {
    fixture.detectChanges();
    component.start({ code: 'TEST', name: 'Test instrument', languages: ['en'] });
    component.answers.set({ 1: 0 });
    component.submit();
    expect(service.submitMine).not.toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalled();
  });

  it('submits the answers, prepends the entry and returns to the list', () => {
    report = () =>
      of({
        available: [{ code: 'TEST', name: 'Test instrument', languages: ['en'] }],
        history: [entry()],
      });
    fixture.detectChanges();
    component.start({ code: 'TEST', name: 'Test instrument', languages: ['en'] });
    component.answers.set({ 1: 0, 2: 2 });
    component.submit();
    fixture.detectChanges();

    expect(service.submitMine).toHaveBeenCalledWith({
      instrumentCode: 'TEST',
      language: 'en',
      answers: { 1: 0, 2: 2 },
    });
    expect(component.active()).toBeNull();
    expect(component.history().map((h) => h.id)).toEqual(['new', 'r1']);
    expect(toast.success).toHaveBeenCalled();
    expect(text()).toContain('PRO.MY.FOLLOW_UP_PLANNED');
  });

  it('keeps the form open and reports the failure when submission fails', () => {
    submitResult = () => throwError(() => new Error('boom'));
    fixture.detectChanges();
    component.start({ code: 'TEST', name: 'Test instrument', languages: ['en'] });
    component.answers.set({ 1: 0, 2: 2 });
    component.submit();
    expect(component.active()).not.toBeNull();
    expect(component.submitting()).toBeFalse();
    expect(toast.error).toHaveBeenCalled();
  });

  it('never renders a score, only whether the care team will follow up', () => {
    report = () =>
      of({
        available: [],
        history: [entry({ careTeamAlerted: true })],
      });
    fixture.detectChanges();
    const body = text();
    expect(body).toContain('PRO.MY.CARE_TEAM_ALERTED');
    expect(body).not.toContain('PRO.SCORE');
    expect(body).not.toMatch(/\d+\s*\/\s*\d+/);
    expect(fixture.nativeElement.querySelector('.screen-alerted')).not.toBeNull();
  });
});
