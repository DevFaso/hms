import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { MyImagingComponent } from './my-imaging.component';
import { PatientImagingService, PatientImagingStudy } from '../../services/patient-imaging.service';
import { PRINT_ROOT_CLASS } from '../../shared/print/print-only';

/**
 * QA D2342 (r63, High): the patient's « Imagerie » page. Asserted through the
 * rendered DOM in French: the signed report, its addendum, the signer and the
 * date, the key images read through the patient's own endpoint, and print.
 */
describe('MyImagingComponent (D2342)', () => {
  let fixture: ComponentFixture<MyImagingComponent>;
  let imaging: jasmine.SpyObj<PatientImagingService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');

  function study(overrides: Partial<PatientImagingStudy> = {}): PatientImagingStudy {
    return {
      imagingOrderId: 'o1',
      modality: 'XRAY',
      studyType: 'Radiographie thoracique',
      bodyRegion: 'Thorax',
      orderedAt: '2026-11-14T09:30:00',
      hospitalName: 'CHU Yalgado',
      reports: [
        {
          id: 'r1',
          reportStatus: 'FINAL',
          reportVersion: 1,
          findings: 'findings-text',
          impression: 'impression-text',
          signedByName: 'Dr Aminata Ouédraogo',
          signedAt: '2026-11-14T10:00:00',
        },
        {
          id: 'r2',
          reportStatus: 'ADDENDUM',
          reportVersion: 2,
          findings: 'addendum-text',
          signedByName: 'Dr Inoussa Belem',
          signedAt: '2026-11-15T08:00:00',
        },
      ],
      images: [
        { id: 'i1', contentType: 'image/png', sizeBytes: 10, caption: 'Face' },
        { id: 'i2', contentType: 'application/pdf', sizeBytes: 10 },
      ],
      ...overrides,
    };
  }

  function create(): void {
    fixture = TestBed.createComponent(MyImagingComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    imaging = jasmine.createSpyObj<PatientImagingService>('PatientImagingService', [
      'studies',
      'image',
    ]);
    imaging.studies.and.returnValue(of([]));
    imaging.image.and.returnValue(of(new Blob(['x'], { type: 'image/png' })));
    TestBed.configureTestingModule({
      imports: [MyImagingComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientImagingService, useValue: imaging }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      IMAGING_COMPLETION: {
        PRINT: 'Imprimer',
        SIGNED_BY_ON: 'Signé par {{name}} le {{date}}',
        PORTAL: {
          TITLE: 'Imagerie',
          EMPTY_TITLE: 'Aucun compte rendu d’imagerie pour l’instant',
          LOAD_FAILED: 'Vos comptes rendus d’imagerie n’ont pas pu être chargés.',
          REPORT: 'Compte rendu',
          IMAGES: 'Images',
          PRESCRIBED_BY: 'Demandé par {{name}}',
        },
      },
      PORTAL: { ENUM: { IMAGING_REPORT_STATUS: { ADDENDUM: 'Addendum' } } },
    });
  });

  it('says there is nothing yet when no report is released', () => {
    create();
    expect(host().querySelector('[data-testid="my-imaging-empty"]')?.textContent).toContain(
      'Aucun compte rendu d’imagerie',
    );
  });

  it('shows the signed report, then its addendum, each with its signer', () => {
    imaging.studies.and.returnValue(of([study()]));
    create();
    expect(texts('h1')[0]).toContain('Imagerie');
    const reports = texts('[data-testid="my-imaging-report"]');
    expect(reports.length).toBe(2);
    expect(reports[0]).toContain('Compte rendu');
    expect(reports[0]).toContain('impression-text');
    expect(reports[1]).toContain('Addendum');
    expect(reports[1]).toContain('addendum-text');
    const signatures = texts('[data-testid="my-imaging-signature"]');
    expect(signatures[0]).toContain('Signé par Dr Aminata Ouédraogo le');
    expect(signatures[1]).toContain('Signé par Dr Inoussa Belem le');
  });

  it('reads each picture through the patient endpoint, never a public URL', () => {
    imaging.studies.and.returnValue(of([study()]));
    create();
    // The PNG is read as a thumbnail; the PDF only when opened.
    expect(imaging.image).toHaveBeenCalledOnceWith('i1', null);
    expect(host().querySelectorAll('[data-testid="my-imaging-images"] .thumb').length).toBe(2);
  });

  it('prints the study alone: its card is the print root while the print runs', () => {
    imaging.studies.and.returnValue(of([study()]));
    create();
    let rootDuringPrint: string | undefined;
    spyOn(window, 'print').and.callFake(() => {
      rootDuringPrint = host().querySelector<HTMLElement>('.' + PRINT_ROOT_CLASS)?.dataset[
        'studyId'
      ];
    });
    host().querySelector<HTMLButtonElement>('[data-testid="my-imaging-print"]')!.click();
    expect(window.print).toHaveBeenCalledTimes(1);
    expect(rootDuringPrint).toBe('o1');
    window.dispatchEvent(new Event('afterprint'));
  });

  it('QA D2643: the printed study names the hospital, the patient and the prescriber', () => {
    const base = study();
    imaging.studies.and.returnValue(
      of([
        study({
          reports: base.reports.map((r) => ({
            ...r,
            patientDisplayName: 'Patient Test 12',
            patientMrn: 'CMT1386',
            patientDateOfBirth: '1990-03-04',
            orderingProviderName: 'Dr Awa Sawadogo',
            hospitalName: 'CHU Yalgado',
            hospitalAddress: 'Ouagadougou',
          })),
        }),
      ]),
    );
    create();
    const card = host().querySelector('[data-study-id="o1"]')!;
    const identity = card.querySelector('[data-testid="print-identity"]')?.textContent ?? '';
    expect(identity).toContain('Patient Test 12');
    expect(identity).toContain('CMT1386');
    expect(identity).toContain('1990');
    expect(identity).toContain('Dr Awa Sawadogo');
    expect(identity).toContain('CHU Yalgado');
    expect(card.querySelector('[data-testid="my-imaging-prescriber"]')?.textContent).toContain(
      'Dr Awa Sawadogo',
    );
    // QA D3153 class: the print header carries the title, so the card's own title prints no second time.
    expect(card.querySelector('[data-testid="my-imaging-study-title"]')!.classList).toContain(
      'no-print',
    );
  });

  it("QA D2641: for a caregiver, reads the grantor's studies and images through the proxy endpoints", () => {
    imaging.studies.and.returnValue(of([study()]));
    fixture = TestBed.createComponent(MyImagingComponent);
    fixture.componentRef.setInput('grantorPatientId', 'grantor-1');
    fixture.componentRef.setInput('embedded', true);
    fixture.detectChanges();
    expect(imaging.studies).toHaveBeenCalledOnceWith('grantor-1');
    expect(imaging.image).toHaveBeenCalledOnceWith('i1', 'grantor-1');
    // Embedded in the family-access viewer: no page title of its own.
    expect(host().querySelector('h1')).toBeNull();
    expect(texts('[data-testid="my-imaging-report"]').length).toBe(2);
  });

  it('says the read failed, with the server reason, never "nothing yet"', () => {
    imaging.studies.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403, error: { message: 'Refusé' } })),
    );
    create();
    expect(host().querySelector('[data-testid="my-imaging-failed"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="my-imaging-empty"]')).toBeNull();
  });
});
