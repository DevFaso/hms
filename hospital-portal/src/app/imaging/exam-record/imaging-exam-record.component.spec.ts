import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ImagingExamRecordComponent } from './imaging-exam-record.component';
import { ImagingOrderResponse, ImagingService } from '../../services/imaging.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';
import { answerReasonDialog } from '../../shared/reason-dialog/reason-dialog.testing';

/** Policy rule 3 (2026-11-20, provisional): the exam record of an imaging order. */
describe('ImagingExamRecordComponent (policy rule 3)', () => {
  let imaging: jasmine.SpyObj<ImagingService>;
  let roles: string[];
  let emitted: ImagingOrderResponse[];

  const order = (over: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse =>
    ({ id: 'o1', status: 'IN_PROGRESS', ...over }) as ImagingOrderResponse;

  function render(o: ImagingOrderResponse): ComponentFixture<ImagingExamRecordComponent> {
    TestBed.configureTestingModule({
      imports: [ImagingExamRecordComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ImagingService, useValue: imaging },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(ImagingExamRecordComponent);
    fixture.componentRef.setInput('order', o);
    emitted = [];
    fixture.componentInstance.changed.subscribe((updated) => emitted.push(updated));
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    return fixture;
  }

  const el = (fixture: ComponentFixture<unknown>, testid: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testid}"]`);

  beforeEach(() => {
    roles = ['ROLE_IMAGING_TECHNICIAN'];
    imaging = jasmine.createSpyObj('ImagingService', [
      'checkInPatient',
      'recordExam',
      'updateOrderStatus',
    ]);
  });

  it('refuses an exam record with nothing in it at the note, and sends nothing', () => {
    const fixture = render(order());
    const noteId = fixture.componentInstance.noteId;
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="imaging-exam-save"]',
      alsoMarked: [noteId],
      sent: imaging.recordExam,
    });
    expect(marked).toEqual([noteId]);
  });

  it('saves the technical note, the contrast and the dose, and hands the updated order back', () => {
    const updated = order({ examTechnicalNote: 'Thorax face' });
    imaging.recordExam.and.returnValue(of(updated));
    const fixture = render(order());
    const c = fixture.componentInstance;
    c.technicalNote = '  Thorax face  ';
    c.contrastAdministered = true;
    c.contrastDetails = 'Iode 80 mL IV';
    c.radiationDoseMgy = 0.15;
    c.save();
    expect(imaging.recordExam).toHaveBeenCalledWith('o1', {
      technicalNote: 'Thorax face',
      contrastAdministered: true,
      contrastDetails: 'Iode 80 mL IV',
      radiationDoseMgy: 0.15,
    });
    expect(emitted).toEqual([updated]);
  });

  it('drops the contrast details when no contrast was given', () => {
    imaging.recordExam.and.returnValue(of(order()));
    const fixture = render(order({ examContrastDetails: 'ancien' }));
    const c = fixture.componentInstance;
    c.technicalNote = 'RAS';
    c.contrastAdministered = false;
    c.save();
    expect(imaging.recordExam).toHaveBeenCalledWith(
      'o1',
      jasmine.objectContaining({ contrastAdministered: false, contrastDetails: null }),
    );
  });

  it("marks the server's refusal at the field it names", () => {
    imaging.recordExam.and.returnValue(
      throwError(() => fieldErrorsResponse({ radiationDoseMgy: 'Dose invalide.' })),
    );
    const fixture = render(order());
    fixture.componentInstance.technicalNote = 'RAS';
    fixture.componentInstance.radiationDoseMgy = 5;
    fixture.componentInstance.save();
    fixture.detectChanges();
    expectFieldsMarked(fixture, { [fixture.componentInstance.doseId]: 'Dose invalide.' });
    expect(emitted).toEqual([]);
  });

  it('checks the patient in once, while the exam is placed and not done', () => {
    const checkedIn = order({ status: 'SCHEDULED', checkedInAt: '2026-11-20T09:00:00' });
    imaging.checkInPatient.and.returnValue(of(checkedIn));
    let fixture = render(order({ status: 'SCHEDULED' }));
    el(fixture, 'imaging-exam-check-in')?.click();
    expect(imaging.checkInPatient).toHaveBeenCalledWith('o1');
    expect(emitted).toEqual([checkedIn]);

    // Already checked in, or the exam is done: no button; the arrival is shown.
    TestBed.resetTestingModule();
    fixture = render(checkedIn);
    expect(el(fixture, 'imaging-exam-check-in')).toBeNull();
    expect(el(fixture, 'imaging-exam-checked-in')).not.toBeNull();
    TestBed.resetTestingModule();
    fixture = render(order({ status: 'COMPLETED' }));
    expect(el(fixture, 'imaging-exam-check-in')).toBeNull();
  });

  it('a technician discontinues a started exam with a reason; never one that has not started', () => {
    const cancelled = order({ status: 'CANCELLED' });
    imaging.updateOrderStatus.and.returnValue(of(cancelled));
    let fixture = render(order({ status: 'IN_PROGRESS' }));
    // The rule is shown as provisional to the technician.
    expect(el(fixture, 'imaging-exam-technician-rule')).not.toBeNull();
    el(fixture, 'imaging-exam-discontinue')?.click();
    fixture.detectChanges();
    answerReasonDialog(fixture, 'Patient agité');
    expect(imaging.updateOrderStatus).toHaveBeenCalledWith('o1', {
      status: 'CANCELLED',
      cancellationReason: 'Patient agité',
    });
    expect(emitted).toEqual([cancelled]);

    TestBed.resetTestingModule();
    fixture = render(order({ status: 'ORDERED' }));
    expect(el(fixture, 'imaging-exam-discontinue')).toBeNull();
  });

  it('a radiologist or a doctor records the exam too, and is not offered the technician’s discontinuation', () => {
    roles = ['ROLE_RADIOLOGIST'];
    let fixture = render(order({ status: 'IN_PROGRESS' }));
    expect(el(fixture, 'imaging-exam-save')).not.toBeNull();
    expect(el(fixture, 'imaging-exam-discontinue')).toBeNull();
    expect(el(fixture, 'imaging-exam-technician-rule')).toBeNull();

    // A doctor who is also a technician keeps the doctor's reach (the server's rule).
    TestBed.resetTestingModule();
    roles = ['ROLE_IMAGING_TECHNICIAN', 'ROLE_DOCTOR'];
    fixture = render(order({ status: 'IN_PROGRESS' }));
    expect(el(fixture, 'imaging-exam-discontinue')).toBeNull();
  });

  it('Ultrasound: a technician neither records nor discontinues it while the hospital keeps it with physicians', () => {
    let fixture = render(
      order({ status: 'IN_PROGRESS', modality: 'ULTRASOUND', technicianMayPerform: false }),
    );
    expect(el(fixture, 'imaging-exam-ultrasound-physicians')).not.toBeNull();
    expect(el(fixture, 'imaging-exam-save')).toBeNull();
    expect(el(fixture, 'imaging-exam-discontinue')).toBeNull();

    // The arrival is not the exam: check-in stays.
    TestBed.resetTestingModule();
    fixture = render(
      order({ status: 'ORDERED', modality: 'ULTRASOUND', technicianMayPerform: false }),
    );
    expect(el(fixture, 'imaging-exam-check-in')).not.toBeNull();
    expect(el(fixture, 'imaging-exam-ultrasound-physicians')).not.toBeNull();

    // The hospital allows it: the technician works it as any other exam.
    TestBed.resetTestingModule();
    fixture = render(
      order({ status: 'IN_PROGRESS', modality: 'ULTRASOUND', technicianMayPerform: true }),
    );
    expect(el(fixture, 'imaging-exam-ultrasound-physicians')).toBeNull();
    expect(el(fixture, 'imaging-exam-save')).not.toBeNull();
    expect(el(fixture, 'imaging-exam-discontinue')).not.toBeNull();

    // A radiologist is never held to the setting.
    TestBed.resetTestingModule();
    roles = ['ROLE_RADIOLOGIST'];
    fixture = render(
      order({ status: 'IN_PROGRESS', modality: 'ULTRASOUND', technicianMayPerform: false }),
    );
    expect(el(fixture, 'imaging-exam-save')).not.toBeNull();
    expect(el(fixture, 'imaging-exam-ultrasound-physicians')).toBeNull();
  });

  it('a nurse reads who performed the exam and its record, and writes nothing', () => {
    roles = ['ROLE_NURSE'];
    const fixture = render(
      order({
        status: 'COMPLETED',
        performedByName: 'Awa Kone',
        examTechnicalNote: 'Thorax face',
        examContrastAdministered: true,
        examContrastDetails: 'Iode 80 mL IV',
        examRadiationDoseMgy: 0.15,
      }),
    );
    expect(el(fixture, 'imaging-exam-performed-by')?.textContent).toContain('Awa Kone');
    expect(el(fixture, 'imaging-exam-note-read')?.textContent).toContain('Thorax face');
    expect(el(fixture, 'imaging-exam-contrast-read')?.textContent).toContain('Iode 80 mL IV');
    expect(el(fixture, 'imaging-exam-dose-read')?.textContent).toContain('0.15');
    expect(el(fixture, 'imaging-exam-save')).toBeNull();
    expect(el(fixture, 'imaging-exam-check-in')).toBeNull();
  });

  it('shows nothing for an order with no exam record that the reader cannot act on', () => {
    roles = ['ROLE_NURSE'];
    const fixture = render(order({ status: 'ORDERED' }));
    expect(el(fixture, 'imaging-exam-record')).toBeNull();
  });

  it('the record is closed before the exam starts and once it is read', () => {
    let fixture = render(order({ status: 'ORDERED' }));
    expect(el(fixture, 'imaging-exam-save')).toBeNull();
    TestBed.resetTestingModule();
    fixture = render(order({ status: 'RESULTS_AVAILABLE', performedByName: 'Awa Kone' }));
    expect(el(fixture, 'imaging-exam-save')).toBeNull();
    expect(el(fixture, 'imaging-exam-performed-by')).not.toBeNull();
  });

  it('fits its box at 1366, 1024, 768 and 390 px wide, long values included', () => {
    const long = 'Protocole thoraco-abdomino-pelvien '.repeat(8) + 'x'.repeat(120);
    const fixture = render(
      order({
        status: 'IN_PROGRESS',
        checkedInAt: '2026-11-20T09:00:00',
        checkedInByName: 'Awa Kone-Ouedraogo-Sawadogo',
        examStartedAt: '2026-11-20T09:05:00',
        performedByName: 'Awa Kone-Ouedraogo-Sawadogo',
        examTechnicalNote: long,
      }),
    );
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    for (const width of [1366, 1024, 768, 390]) {
      // The drawer's content box: the screen less its gutters, never wider than the drawer.
      host.style.width = `${Math.min(width - 32, 640)}px`;
      fixture.detectChanges();
      const section = el(fixture, 'imaging-exam-record')!;
      expect(section.scrollWidth)
        .withContext(`exam record at ${width}px`)
        .toBeLessThanOrEqual(section.clientWidth + 1);
      expect(section.getBoundingClientRect().width)
        .withContext(`exam record box at ${width}px`)
        .toBeLessThanOrEqual(host.getBoundingClientRect().width + 1);
    }
  });
});
