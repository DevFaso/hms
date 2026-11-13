import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { AfterVisitSummary } from '../../services/encounter.service';
import { VisitSummaryDialogComponent } from './visit-summary-dialog.component';

describe('VisitSummaryDialogComponent', () => {
  const summary: AfterVisitSummary = {
    encounterId: 'cdx-encounter',
    patientName: 'Cdx Patient',
    visitDate: '2026-11-07T12:00:00',
    providerName: 'Cdx Clinician',
    departmentName: 'Cdx Department',
    hospitalName: 'Cdx Hospital',
    chiefComplaint: 'Cdx complaint\nSecond line',
    dischargeDiagnoses: ['Cdx diagnosis A', 'Cdx diagnosis B'],
    prescriptionSummary: 'Cdx prescription text',
    referralSummary: 'Cdx referral text',
    followUpInstructions: 'Cdx follow-up text',
    patientEducationMaterials: 'Cdx education text',
    followUpAppointmentDate: '2026-11-14',
    encounterStatus: 'COMPLETED',
    checkoutTimestamp: '2026-11-07T12:30:00',
  };

  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [VisitSummaryDialogComponent, TranslateModule.forRoot()],
    }),
  );

  function render(value: AfterVisitSummary) {
    const fixture = TestBed.createComponent(VisitSummaryDialogComponent);
    fixture.componentRef.setInput('summary', value);
    fixture.detectChanges();
    return fixture;
  }

  it('preserves every returned clinical section and its free text', () => {
    const fixture = render(summary);
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelectorAll('.avs-section').length).toBe(7);
    expect(root.querySelector('#avs-patient')?.textContent).toContain('Cdx Patient');
    expect(root.querySelector('[data-testid="avs-complaint"] p')?.textContent).toBe(
      summary.chiefComplaint!,
    );
    expect(
      Array.from(root.querySelectorAll('[data-testid="avs-diagnoses"] li')).map(
        (li) => li.textContent,
      ),
    ).toEqual(summary.dischargeDiagnoses);
    for (const text of [
      summary.prescriptionSummary,
      summary.referralSummary,
      summary.followUpInstructions,
      summary.patientEducationMaterials,
    ]) {
      expect(root.textContent).toContain(text!);
    }
    // The follow-up date reads in the reader's format, never the wire's yyyy-MM-dd.
    const appointment = root.querySelector('[data-testid="avs-appointment"] p')?.textContent ?? '';
    expect(appointment).toContain('2026');
    expect(appointment).not.toContain(summary.followUpAppointmentDate!);
    expect(root.querySelector('[role="dialog"]')?.getAttribute('aria-labelledby')).toBe(
      'avs-title',
    );
  });

  it('does not invent clinical sections when they are absent', () => {
    const fixture = render({
      encounterId: 'cdx-empty',
      visitDate: '',
      providerName: '',
      departmentName: '',
      hospitalName: '',
      dischargeDiagnoses: [],
      encounterStatus: '',
      checkoutTimestamp: '',
    });
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelectorAll('.avs-section').length).toBe(0);
    expect(root.querySelector('.avs-empty')).not.toBeNull();
    expect(root.querySelector('#avs-patient')?.textContent).toContain('VISIT_SUMMARY.NOT_PROVIDED');
  });

  it('2026-11-12: shows the content sections, and a summary with only those is not empty', () => {
    const fixture = render({
      encounterId: 'cdx-sections',
      visitDate: '',
      providerName: '',
      departmentName: '',
      hospitalName: '',
      dischargeDiagnoses: [],
      encounterStatus: '',
      checkoutTimestamp: '',
      currentMedications: [{ name: 'Cdx medicine' }],
    });
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[data-testid="avs-medications"]')?.textContent).toContain(
      'Cdx medicine',
    );
    expect(root.querySelector('.avs-empty')).toBeNull();
  });

  it('emits close from both buttons without modifying the summary', () => {
    const fixture = render(summary);
    const close = jasmine.createSpy('close');
    fixture.componentInstance.closed.subscribe(close);
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('[data-testid="avs-close"]') as HTMLButtonElement).click();
    (root.querySelector('[data-testid="avs-done"]') as HTMLButtonElement).click();
    expect(close).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.summary()).toBe(summary);
  });
});
