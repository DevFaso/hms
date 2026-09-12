import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MySharingComponent } from './my-sharing.component';
import {
  AccessLogEntry,
  DisclosureAccounting,
  PatientPortalService,
  PatientProfileDTO,
  RecordSharingOptOut,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

/**
 * "Who accessed my record" (E9 #66). The access-log half of these specs
 * exists because an earlier suite stubbed the log as empty and never
 * rendered a row, so bindings that matched nothing the backend sent went
 * unnoticed (Tier 2 item 39). The opt-out half pins the one control the
 * page still offers.
 */
describe('MySharingComponent', () => {
  let component: MySharingComponent;
  let fixture: ComponentFixture<MySharingComponent>;

  const entry = (over: Partial<AccessLogEntry> = {}): AccessLogEntry => ({
    id: 'a1',
    actor: 'Dr Alice Traore',
    actorRole: 'Doctor',
    hospitalName: 'City Clinic',
    eventType: 'PATIENT_ACCESS',
    entityType: 'PATIENT',
    resourceId: 'p1',
    description: 'Doctor record view',
    status: 'SUCCESS',
    timestamp: '2026-08-20T10:30:00',
    category: 'TREATMENT_ACCESS',
    externalDisclosure: false,
    ...over,
  });

  const accounting = (
    entries: AccessLogEntry[],
    over: Partial<DisclosureAccounting> = {},
  ): DisclosureAccounting => ({
    from: null,
    to: null,
    countsByCategory: {},
    totalEvents: entries.length,
    externalDisclosures: 0,
    entries,
    totalPages: 1,
    page: 0,
    ...over,
  });

  const optOutState = (inForce: boolean): RecordSharingOptOut => ({
    patientId: 'p-1',
    inForce,
    optedOutAt: inForce ? '2026-09-12T08:00:00' : null,
    reason: inForce ? 'Je préfère' : null,
    revokedAt: null,
  });

  let disclosureResponse: () => ReturnType<PatientPortalService['getMyDisclosures']>;
  let optOutResponse: () => ReturnType<PatientPortalService['getMyOptOut']>;
  let portal: jasmine.SpyObj<PatientPortalService>;

  const mockToast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    disclosureResponse = () => of(accounting([]));
    optOutResponse = () => of(optOutState(false));
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyDisclosures',
      'getMyProfile',
      'getMyOptOut',
      'optOutOfSharing',
      'revokeOptOut',
    ]);
    portal.getMyDisclosures.and.callFake(() => disclosureResponse());
    portal.getMyProfile.and.returnValue(of({ id: 'p-1' } as PatientProfileDTO));
    portal.getMyOptOut.and.callFake(() => optOutResponse());
    portal.optOutOfSharing.and.returnValue(of(optOutState(true)));
    portal.revokeOptOut.and.returnValue(of(optOutState(false)));
    mockToast.success.calls.reset();
    mockToast.error.calls.reset();

    await TestBed.configureTestingModule({
      imports: [MySharingComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portal },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MySharingComponent);
    component = fixture.componentInstance;
  });

  function el(testId: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`);
  }

  // ── Opt-out ──

  it('loads the opt-out for the signed-in patient and offers to close the door', () => {
    fixture.detectChanges();

    expect(portal.getMyOptOut).toHaveBeenCalledOnceWith('p-1');
    expect(component.optedOut()).toBeFalse();
    expect(el('opt-out-enable')).not.toBeNull();
    expect(el('opt-out-revoke')).toBeNull();
  });

  it('asks for a reason, then opts out with the stated reason and shows the new state', () => {
    fixture.detectChanges();

    el('opt-out-enable')!.click();
    fixture.detectChanges();
    expect(el('opt-out-form')).not.toBeNull();

    component.optOutReason = '  Je préfère  ';
    el('opt-out-confirm')!.click();
    fixture.detectChanges();

    expect(portal.optOutOfSharing).toHaveBeenCalledOnceWith('p-1', 'Je préfère');
    expect(component.optedOut()).toBeTrue();
    expect(el('opt-out-form')).toBeNull();
    expect(el('opt-out-revoke')).not.toBeNull();
    expect(mockToast.success).toHaveBeenCalledWith('PORTAL.SHARING.OPT_OUT.SAVED_ON');
  });

  it('sends no reason when the patient leaves it blank', () => {
    fixture.detectChanges();
    component.openOptOutForm();
    component.confirmOptOut();

    expect(portal.optOutOfSharing).toHaveBeenCalledOnceWith('p-1', null);
  });

  it('reopens sharing from an opted-out state', () => {
    optOutResponse = () => of(optOutState(true));
    fixture.detectChanges();
    expect(component.optedOut()).toBeTrue();

    el('opt-out-revoke')!.click();
    fixture.detectChanges();

    expect(portal.revokeOptOut).toHaveBeenCalledOnceWith('p-1');
    expect(component.optedOut()).toBeFalse();
    expect(mockToast.success).toHaveBeenCalledWith('PORTAL.SHARING.OPT_OUT.SAVED_OFF');
  });

  it('shows a failure instead of "sharing is on" when the opt-out cannot be loaded', () => {
    optOutResponse = () => throwError(() => new Error('500'));
    fixture.detectChanges();

    expect(component.optOutFailed()).toBeTrue();
    expect(el('opt-out-failed')).not.toBeNull();
    expect(el('opt-out-enable')).toBeNull();
    expect(el('opt-out-status')).toBeNull();
  });

  it('keeps the current state and says so when saving fails', () => {
    portal.optOutOfSharing.and.returnValue(throwError(() => new Error('409')));
    fixture.detectChanges();
    component.openOptOutForm();
    component.confirmOptOut();

    expect(component.optedOut()).toBeFalse();
    expect(component.optOutSaving()).toBeFalse();
    expect(mockToast.error).toHaveBeenCalledWith('PORTAL.SHARING.OPT_OUT.FAILED');
  });

  // ── Access log ──

  it('loads the access log on arrival, without a tab to find first', () => {
    disclosureResponse = () => of(accounting([entry()]));
    fixture.detectChanges();

    expect(portal.getMyDisclosures).toHaveBeenCalledTimes(1);
    expect(component.accessLog().length).toBe(1);
  });

  it('renders the name of whoever accessed the record', () => {
    // THE REGRESSION. With the old bindings this row rendered as an empty
    // title and a bare " · " separator, and the only way to see it was to
    // put a row on the page — which no test did.
    disclosureResponse = () => of(accounting([entry()]));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Dr Alice Traore');
    expect(text).toContain('City Clinic');
  });

  it('marks an emergency access so it is not lost among routine views', () => {
    disclosureResponse = () =>
      of(
        accounting(
          [
            entry({ id: 'e1', category: 'EMERGENCY_ACCESS', eventType: 'BREAK_GLASS_ACCESS' }),
            entry({ id: 'e2' }),
          ],
          { countsByCategory: { EMERGENCY_ACCESS: 1, TREATMENT_ACCESS: 1 } },
        ),
      );
    fixture.detectChanges();

    const emergency = (fixture.nativeElement as HTMLElement).querySelectorAll('.pli-emergency');
    expect(emergency.length).toBe(1);
  });

  it('marks a release to another hospital as an external disclosure', () => {
    disclosureResponse = () =>
      of(
        accounting([entry({ category: 'SHARED_WITH_PROVIDER', externalDisclosure: true })], {
          externalDisclosures: 1,
        }),
      );
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.pli-external').length).toBe(1);
  });

  it('shows a failure state instead of telling the patient nobody looked', () => {
    // The service used to catchError into an empty array, so an outage
    // rendered as "Nobody has accessed your records yet" — a false
    // statement about a privacy-critical fact, not a blank screen.
    disclosureResponse = () => throwError(() => new Error('500'));
    fixture.detectChanges();

    expect(component.logFailed()).toBe(true);
    expect(component.accessLog().length).toBe(0);
  });

  it('retries after a failure rather than caching the failure as an answer', () => {
    disclosureResponse = () => throwError(() => new Error('500'));
    fixture.detectChanges();
    expect(component.logFailed()).toBe(true);

    disclosureResponse = () => of(accounting([entry()]));
    component.retryAccessLog();
    fixture.detectChanges();

    expect(component.logFailed()).toBe(false);
    expect(component.accessLog().length).toBe(1);
  });

  it('leads with the counts that matter across the whole history', () => {
    // The counts come from a grouped query over the entire window, not from
    // the loaded page — a patient with one emergency access six months and
    // 400 chart-opens ago must see the 1 without paging to it.
    disclosureResponse = () =>
      of(
        accounting([entry()], {
          countsByCategory: { EMERGENCY_ACCESS: 2, TREATMENT_ACCESS: 400 },
          externalDisclosures: 3,
          totalEvents: 405,
        }),
      );
    fixture.detectChanges();

    expect(component.emergencyCount()).toBe(2);
    expect(component.externalCount()).toBe(3);
    const band = (fixture.nativeElement as HTMLElement).querySelector('.access-summary');
    expect(band).toBeTruthy();
    expect(band?.textContent).toContain('2');
    expect(band?.textContent).toContain('3');
  });

  it('hides the summary band when there is nothing in it', () => {
    // A row of zeroes on every ordinary account trains people to skip past
    // the band on the one visit it matters.
    disclosureResponse = () => of(accounting([entry()]));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.access-summary')).toBeNull();
  });

  it('states what the log covers in every state, including when it is empty', () => {
    // Routine chart reads emit no audit event, so this list contains only
    // disclosures. Without the note, an empty page reads as "nobody looked"
    // and a populated one reads as "this is everyone who looked" — both
    // false, and about a privacy fact the patient cannot verify elsewhere.
    const note = () => (fixture.nativeElement as HTMLElement).querySelector('.access-scope-note');

    disclosureResponse = () => of(accounting([]));
    fixture.detectChanges();
    expect(note()).withContext('empty state').toBeTruthy();

    disclosureResponse = () => of(accounting([entry()]));
    component.retryAccessLog();
    fixture.detectChanges();
    expect(note()).withContext('populated state').toBeTruthy();

    disclosureResponse = () => throwError(() => new Error('500'));
    component.retryAccessLog();
    fixture.detectChanges();
    expect(note()).withContext('failure state').toBeTruthy();
  });

  it('falls back to a neutral label when the backend sends no category', () => {
    expect(component.categoryLabelKey(entry({ category: null }))).toBe(
      'PORTAL.SHARING.CATEGORY.UNKNOWN',
    );
    expect(component.categoryLabelKey(entry({ category: 'INSURANCE' }))).toBe(
      'PORTAL.SHARING.CATEGORY.INSURANCE',
    );
  });
});
