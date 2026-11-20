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
    // A midwife does not assign beds (AdmissionController#assignBed): the card says who does.
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="maternity-stay-who-assigns"]',
      ),
    ).not.toBeNull();
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

  it('a newborn is admitted by a doctor; a midwife is told so instead of a button the server refuses', () => {
    let fixture = render('NEWBORN');
    let host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-admit"]')).toBeNull();
    expect(host.querySelector('[data-testid="maternity-stay-who-admits"]')).not.toBeNull();
    TestBed.resetTestingModule();
    roles = ['ROLE_DOCTOR'];
    fixture = render('NEWBORN');
    host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="maternity-stay-admit"]')).not.toBeNull();
  });
});
