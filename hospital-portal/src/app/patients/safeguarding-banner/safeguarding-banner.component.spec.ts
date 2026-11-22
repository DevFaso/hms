import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { SafeguardingReview, SafeguardingService } from '../../services/safeguarding.service';
import { expectEmptySubmitRefusal, expectFieldsMarked } from '../../testing/form-refusal-behaviour';
import { SafeguardingBannerComponent } from './safeguarding-banner.component';

/** r53 (provisional rule 2026-11-14): the open safeguarding review on a minor's chart, for the treating team only. */
describe('SafeguardingBannerComponent', () => {
  let fixture: ComponentFixture<SafeguardingBannerComponent>;
  let safeguarding: jasmine.SpyObj<SafeguardingService>;
  let held: string[];

  const open: SafeguardingReview = {
    id: 'r1',
    patientId: 'patient-1',
    triggerCode: 'MINOR_LABOUR',
    sourceType: 'ADMISSION',
    patientAgeYears: 10,
    ageThresholdYears: 13,
    provisionalRule: true,
    status: 'OPEN',
    raisedAt: '2026-11-14T18:00:00',
  };

  function render(answer: ReturnType<SafeguardingService['forPatient']>): HTMLElement {
    safeguarding.forPatient.and.returnValue(answer);
    fixture = TestBed.createComponent(SafeguardingBannerComponent);
    fixture.componentRef.setInput('patientId', 'patient-1');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    held = ['ROLE_DOCTOR'];
    safeguarding = jasmine.createSpyObj<SafeguardingService>('SafeguardingService', [
      'forPatient',
      'recordReview',
    ]);
    TestBed.configureTestingModule({
      imports: [SafeguardingBannerComponent, TranslateModule.forRoot()],
      providers: [
        { provide: SafeguardingService, useValue: safeguarding },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (roles: string[]) => roles.some((role) => held.includes(role)),
            // V356: the « provisoire » label reads the acting hospital; none here, the screen's wording stays.
            effectiveHospitalIdForRequest: () => null,
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
  });

  it('shows the open review with neutral wording, and a doctor records it', () => {
    const el = render(of([open]));
    expect(safeguarding.forPatient).toHaveBeenCalledWith('patient-1');
    const section = el.querySelector('[data-testid="safeguarding-banner"]');
    expect(section).not.toBeNull();
    expect(section?.getAttribute('role')).toBe('status');
    expect(el.textContent).toContain('SAFEGUARDING.TITLE');
    expect(el.textContent).toContain('SAFEGUARDING.CONFIDENTIAL');
    expect(el.textContent).toContain('SAFEGUARDING.PROVISIONAL');
    expect(el.querySelector('[data-testid="safeguarding-record"]')).not.toBeNull();

    safeguarding.recordReview.and.returnValue(of({ ...open, status: 'REVIEWED' }));
    safeguarding.forPatient.and.returnValue(of([{ ...open, status: 'REVIEWED' }]));
    fixture.componentInstance.note.set('  Seen with the safeguarding lead.  ');
    fixture.componentInstance.record(open);
    fixture.detectChanges();
    expect(safeguarding.recordReview).toHaveBeenCalledWith(
      'r1',
      'Seen with the safeguarding lead.',
    );
    expect(el.querySelector('[data-testid="safeguarding-banner"]')).toBeNull();
  });

  it('an empty review is marked and nothing is sent', () => {
    render(of([open]));
    document.body.appendChild(fixture.nativeElement);
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="safeguarding-record"]',
      sent: safeguarding.recordReview,
    });
    expect(marked).toContain('sgr-note-r1');
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('marks the note the server refuses and keeps the text', () => {
    render(of([open]));
    document.body.appendChild(fixture.nativeElement);
    safeguarding.recordReview.and.returnValue(
      throwError(() => ({ status: 400, error: { fieldErrors: { note: 'Note refused' } } })),
    );
    fixture.componentInstance.note.set('x');
    fixture.componentInstance.record(open);
    fixture.detectChanges();
    expectFieldsMarked(fixture, { 'sgr-note-r1': 'Note refused' });
    expect(fixture.componentInstance.note()).toBe('x');
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('a nurse reads the review but has no record button', () => {
    held = ['ROLE_NURSE'];
    const el = render(of([open]));
    expect(el.querySelector('[data-testid="safeguarding-banner"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="safeguarding-record"]')).toBeNull();
  });

  it('reception, billing and the pharmacy never ask for it', () => {
    for (const role of [
      'ROLE_RECEPTIONIST',
      'ROLE_BILLING_SPECIALIST',
      'ROLE_PHARMACIST',
      'ROLE_PATIENT',
    ]) {
      held = [role];
      safeguarding.forPatient.calls.reset();
      const el = render(of([open]));
      expect(safeguarding.forPatient).withContext(role).not.toHaveBeenCalled();
      expect(el.querySelector('[data-testid="safeguarding-banner"]')).withContext(role).toBeNull();
    }
  });

  it('a recorded review shows nothing', () => {
    const el = render(of([{ ...open, status: 'REVIEWED' }]));
    expect(el.querySelector('[data-testid="safeguarding-banner"]')).toBeNull();
  });

  it('shows nothing, and never breaks the chart, when the read fails', () => {
    const el = render(throwError(() => new Error('403')));
    expect(el.querySelector('[data-testid="safeguarding-banner"]')).toBeNull();
  });
});
