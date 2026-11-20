import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MaternityStayComponent, MaternityStayKind } from './maternity-stay.component';
import { AdmissionResponse, AdmissionService } from '../services/admission.service';
import { BedService } from '../services/bed.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../testing/form-refusal-behaviour';

/** QA D2594 (r65): a woman in labour and her newborn get a stay and a bed from the labour screen. */
describe('MaternityStayComponent (QA D2594)', () => {
  let admissions: jasmine.SpyObj<AdmissionService>;
  let beds: jasmine.SpyObj<BedService>;
  let roles: string[];

  const stay = (roomBed: string | null): AdmissionResponse =>
    ({ id: 'adm1', patientId: 'p1', roomBed }) as unknown as AdmissionResponse;

  function render(kind: MaternityStayKind = 'LABOR_DELIVERY') {
    TestBed.configureTestingModule({
      imports: [MaternityStayComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: AdmissionService, useValue: admissions },
        { provide: BedService, useValue: beds },
        { provide: AuthService, useValue: { getUserProfile: () => ({ staffId: 's1' }) } },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
            effectiveHospitalIdForRequest: () => 'h1',
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(MaternityStayComponent);
    fixture.componentRef.setInput('patientId', 'p1');
    fixture.componentRef.setInput('patientName', 'Patient Test 04');
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('admittedAt', '2026-11-20T00:22:00');
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    roles = ['ROLE_MIDWIFE'];
    admissions = jasmine.createSpyObj('AdmissionService', [
      'getCurrentByPatient',
      'create',
      'assignBed',
      'getByPatient',
    ]);
    beds = jasmine.createSpyObj('BedService', ['getAvailableBeds']);
    admissions.getCurrentByPatient.and.returnValue(of(null));
    beds.getAvailableBeds.and.returnValue(of([{ id: 'b1', label: 'MAT65A/M-01' } as never]));
  });

  it('says a woman in labour is not admitted, and refuses an admission with no acuity at the field', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-none"]')?.textContent).toContain(
      'Patient Test 04',
    );
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="maternity-stay-admit"]',
      sent: admissions.create,
    });
    expect(marked).toEqual(['stay-acuity-p1']);
  });

  it('admits her as a labour stay at the labour admission time, the midwife as the admitting clinician', () => {
    admissions.create.and.returnValue(of(stay(null)));
    const fixture = render();
    fixture.componentInstance.acuity = 'LEVEL_3_MAJOR';
    fixture.componentInstance.admit();
    expect(admissions.create).toHaveBeenCalledWith(
      jasmine.objectContaining({
        patientId: 'p1',
        hospitalId: 'h1',
        admittingProviderId: 's1',
        admissionType: 'LABOR_DELIVERY',
        acuityLevel: 'LEVEL_3_MAJOR',
        admissionDateTime: '2026-11-20T00:22:00',
      }),
    );
    fixture.detectChanges();
    // Policy rule 2 (provisional): the midwife assigns the maternity bed herself, and is told the rule.
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-who-assigns"]')).toBeNull();
    expect(host.querySelector('[data-testid="maternity-stay-bed-select"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="maternity-stay-midwife-bed-rule"]')).not.toBeNull();
  });

  it("marks the server's refusal at the acuity", () => {
    admissions.create.and.returnValue(
      throwError(() => fieldErrorsResponse({ acuityLevel: 'Choisissez un niveau.' })),
    );
    const fixture = render();
    fixture.componentInstance.acuity = 'LEVEL_2_MODERATE';
    fixture.componentInstance.admit();
    fixture.detectChanges();
    expectFieldsMarked(fixture, { 'stay-acuity-p1': 'Choisissez un niveau.' });
  });

  it('a nurse assigns the bed of an admitted mother; an empty pick is refused at the field', () => {
    roles = ['ROLE_NURSE'];
    admissions.getCurrentByPatient.and.returnValue(of(stay(null)));
    admissions.assignBed.and.returnValue(of(stay('MAT65A/M-01')));
    const fixture = render();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="maternity-stay-assign"]',
      sent: admissions.assignBed,
    });
    expect(marked).toEqual(['stay-bed-p1']);
    fixture.componentInstance.bedId = 'b1';
    fixture.componentInstance.assignBed(stay(null));
    fixture.detectChanges();
    expect(admissions.assignBed).toHaveBeenCalledWith('adm1', 'b1');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="maternity-stay-bed"]')
        ?.textContent,
    ).toContain('Patient Test 04');
  });

  it("marks the server's refusal at the bed", () => {
    roles = ['ROLE_NURSE'];
    admissions.getCurrentByPatient.and.returnValue(of(stay(null)));
    admissions.assignBed.and.returnValue(
      throwError(() => fieldErrorsResponse({ bedId: 'Lit occupé.' })),
    );
    const fixture = render();
    fixture.componentInstance.bedId = 'b1';
    fixture.componentInstance.assignBed(stay(null));
    fixture.detectChanges();
    expectFieldsMarked(fixture, { 'stay-bed-p1': 'Lit occupé.' });
  });

  it('Policy rule 2: a midwife admits the newborn as its responsible clinician; a nurse is told who does', () => {
    admissions.create.and.returnValue(of(stay(null)));
    let fixture = render('NEWBORN');
    let host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-admit"]')).not.toBeNull();
    // The rule is shown as provisional where it applies.
    expect(
      host.querySelector('[data-testid="maternity-stay-midwife-newborn-rule"]'),
    ).not.toBeNull();
    fixture.componentInstance.acuity = 'LEVEL_1_MINIMAL';
    fixture.componentInstance.admit();
    expect(admissions.create).toHaveBeenCalledWith(
      jasmine.objectContaining({
        patientId: 'p1',
        admittingProviderId: 's1',
        admissionType: 'NEWBORN',
      }),
    );

    TestBed.resetTestingModule();
    roles = ['ROLE_DOCTOR'];
    fixture = render('NEWBORN');
    host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-admit"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="maternity-stay-midwife-newborn-rule"]')).toBeNull();

    // A nurse gains no newborn admission: no button, and the card says who admits.
    TestBed.resetTestingModule();
    roles = ['ROLE_NURSE'];
    fixture = render('NEWBORN');
    host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-admit"]')).toBeNull();
    expect(host.querySelector('[data-testid="maternity-stay-who-admits"]')).not.toBeNull();
  });

  it("Policy rule 2: the server's refusal of a newborn that is not hers is shown as worded", () => {
    admissions.create.and.returnValue(
      throwError(() => ({ error: { message: 'Ce patient est admis par un médecin.' } })),
    );
    const fixture = render('NEWBORN');
    fixture.componentInstance.acuity = 'LEVEL_1_MINIMAL';
    fixture.componentInstance.admit();
    fixture.detectChanges();
    expect(fixture.componentInstance.stay()).toBeNull();
    expect(fixture.componentInstance.saving()).toBeFalse();
  });

  it("Policy rule 2: a midwife's bed picker holds the maternity wards' beds, and a neonatal cot for a newborn", () => {
    const free = [
      { id: 'm1', label: 'MAT/M-01', wardType: 'MATERNITY' },
      { id: 'g1', label: 'GEN/G-01', wardType: 'GENERAL' },
      { id: 'n1', label: 'NEO/N-01', wardType: 'NICU' },
      { id: 'x1', label: 'OLD/X-01' },
    ] as never[];
    beds.getAvailableBeds.and.returnValue(of(free));
    admissions.getCurrentByPatient.and.returnValue(of(stay(null)));

    // A woman in labour: maternity beds (and a bed an older server sent with no ward type).
    let fixture = render('LABOR_DELIVERY');
    expect(fixture.componentInstance.beds().map((b) => b.id)).toEqual(['m1', 'x1']);

    // A newborn: the neonatal cot too, and assigning it tells her a doctor is asked.
    TestBed.resetTestingModule();
    fixture = render('NEWBORN');
    expect(fixture.componentInstance.beds().map((b) => b.id)).toEqual(['m1', 'n1', 'x1']);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="maternity-stay-midwife-bed-rule"]',
      ),
    ).not.toBeNull();
    admissions.assignBed.and.returnValue(of(stay('NEO/N-01')));
    fixture.componentInstance.bedId = 'n1';
    fixture.componentInstance.assignBed(stay(null));
    expect(admissions.assignBed).toHaveBeenCalledWith('adm1', 'n1');

    // A nurse (or a midwife who is also one) keeps every ward's beds.
    TestBed.resetTestingModule();
    roles = ['ROLE_MIDWIFE', 'ROLE_NURSE'];
    fixture = render('NEWBORN');
    expect(fixture.componentInstance.beds().length).toBe(4);
  });

  it('Review of #1237: a failed stay lookup says so, never « non admise »', () => {
    admissions.getCurrentByPatient.and.returnValue(throwError(() => new Error('boom')));
    const host = render().nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-failed"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="maternity-stay-none"]')).toBeNull();
  });

  it('Review of #1237: a past delivery offers no admission once a stay began after the birth, and admits now', () => {
    admissions.getByPatient.and.returnValue(
      of([{ id: 'old', admissionDateTime: '2026-11-21T08:00:00' } as unknown as AdmissionResponse]),
    );
    TestBed.configureTestingModule({
      imports: [MaternityStayComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: AdmissionService, useValue: admissions },
        { provide: BedService, useValue: beds },
        { provide: AuthService, useValue: { getUserProfile: () => ({ staffId: 's1' }) } },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
            effectiveHospitalIdForRequest: () => 'h1',
          },
        },
      ],
    });
    let fixture = TestBed.createComponent(MaternityStayComponent);
    fixture.componentRef.setInput('patientId', 'p1');
    fixture.componentRef.setInput('onlyIfNoStaySince', '2026-11-20T00:22:00');
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.maternity-stay')).toBeNull();

    // No stay since the birth: offered, and admitted at the current time (no admittedAt passed).
    admissions.getByPatient.and.returnValue(of([]));
    admissions.create.and.returnValue(of(stay(null)));
    fixture = TestBed.createComponent(MaternityStayComponent);
    fixture.componentRef.setInput('patientId', 'p1');
    fixture.componentRef.setInput('onlyIfNoStaySince', '2026-11-20T00:22:00');
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="maternity-stay-admit"]'),
    ).not.toBeNull();
    fixture.componentInstance.acuity = 'LEVEL_2_MODERATE';
    fixture.componentInstance.admit();
    const sent = admissions.create.calls.mostRecent().args[0];
    expect(sent.admissionDateTime).not.toBe('2026-11-20T00:22:00');
  });

  it('Policy rule 2: the card and its provisional rule fit at 1366, 1024, 768 and 390 px wide', () => {
    admissions.getCurrentByPatient.and.returnValue(of(stay(null)));
    const fixture = render('NEWBORN');
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    for (const width of [1366, 1024, 768, 390]) {
      host.style.width = `${width - 32}px`;
      fixture.detectChanges();
      const card = host.querySelector<HTMLElement>('.maternity-stay')!;
      expect(card.scrollWidth)
        .withContext(`stay card at ${width}px`)
        .toBeLessThanOrEqual(card.clientWidth + 1);
    }
  });
});
