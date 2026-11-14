import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import * as axe from 'axe-core';
import { AfterVisitSummary } from '../../services/encounter.service';
import { PRINT_ISOLATED_CLASS, PRINT_ROOT_CLASS } from '../../shared/print/print-only';
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

  afterEach(() => document.body.classList.remove(PRINT_ISOLATED_CLASS));

  function render(value: AfterVisitSummary) {
    const fixture = TestBed.createComponent(VisitSummaryDialogComponent);
    fixture.componentRef.setInput('summary', value);
    fixture.detectChanges();
    return fixture;
  }

  it('preserves every returned clinical section and its free text', () => {
    const fixture = render(summary);
    const root: HTMLElement = fixture.nativeElement;
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
    const appointment =
      root.querySelector('[data-testid="avs-appointment-date"]')?.textContent ?? '';
    expect(appointment).toContain('2026');
    expect(appointment).not.toContain(summary.followUpAppointmentDate!);
    expect(root.querySelector('[role="dialog"]')?.getAttribute('aria-labelledby')).toBe(
      'avs-title',
    );
  });

  // Polish 2026-11-13: the name and the read-only notice each appear once.
  it('names the patient once and says "read-only" once, in the footer', () => {
    const root: HTMLElement = render(summary).nativeElement;
    expect(root.textContent!.split('Cdx Patient').length - 1).toBe(1);
    expect(root.textContent!.split('VISIT_SUMMARY.READ_ONLY').length - 1).toBe(1);
    expect(root.querySelector('footer [data-testid="avs-read-only"]')?.textContent).toContain(
      'VISIT_SUMMARY.READ_ONLY',
    );
  });

  // Provisional platform rule: the visit reads "not finalised" while a note waits for its signature.
  it('shows the not-finalised banner while a note is unsigned, and not once it is signed', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      VISIT_SUMMARY: { PENDING_SIGNATURE: 'Non finalisé — en attente de signature du praticien' },
    });
    translate.use('fr');
    const pending = render({ ...summary, pendingSignature: true });
    expect(
      pending.nativeElement.querySelector('[data-testid="avs-pending-signature"]')?.textContent,
    ).toContain('Non finalisé — en attente de signature du praticien');

    const signed = render({ ...summary, pendingSignature: false });
    expect(signed.nativeElement.querySelector('[data-testid="avs-pending-signature"]')).toBeNull();
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
    expect(root.querySelectorAll('.vss-section').length).toBe(0);
    expect(root.querySelector('.avs-empty')).not.toBeNull();
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

  it('shows contact-only summaries without the empty-summary message', () => {
    const fixture = render({
      encounterId: 'contact-only',
      visitDate: '',
      providerName: '',
      departmentName: '',
      hospitalName: '',
      dischargeDiagnoses: [],
      encounterStatus: '',
      checkoutTimestamp: '',
      hospitalPhone: '5550100',
    });
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('5550100');
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

  it('prints the summary alone: the dialog is the print root while the print runs', () => {
    const root: HTMLElement = render(summary).nativeElement;
    const dialog = root.querySelector('[data-testid="visit-summary-dialog"]')!;
    let marked = false;
    spyOn(window, 'print').and.callFake(() => {
      marked =
        dialog.classList.contains(PRINT_ROOT_CLASS) &&
        document.body.classList.contains(PRINT_ISOLATED_CLASS);
    });
    (root.querySelector('[data-testid="avs-print"]') as HTMLButtonElement).click();
    expect(window.print).toHaveBeenCalledTimes(1);
    expect(marked).toBeTrue();

    window.dispatchEvent(new Event('afterprint'));
    expect(dialog.classList.contains(PRINT_ROOT_CLASS)).toBeFalse();
    expect(document.body.classList.contains(PRINT_ISOLATED_CLASS)).toBeFalse();
  });

  /**
   * Print-CSS smoke: the rules the browser applies on paper. The buttons, the
   * shadow, the fixed backdrop and the scroll box go; each section keeps
   * together across a page break.
   */
  it('ships print rules that hide the buttons and flatten the dialog', () => {
    render(summary);
    const printRules: string[] = [];
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try {
        rules = sheet.cssRules;
      } catch {
        continue;
      }
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule && rule.media.mediaText.includes('print')) {
          printRules.push(...Array.from(rule.cssRules).map((r) => r.cssText));
        }
      }
    }
    const ruleFor = (selector: string) =>
      printRules.filter((text) => text.split('{')[0].includes(selector)).join('\n');
    expect(ruleFor('.avs-close')).toContain('display: none');
    expect(ruleFor('.avs-actions')).toContain('display: none');
    expect(ruleFor('.avs-dialog')).toContain('box-shadow: none');
    expect(ruleFor('.avs-dialog')).toContain('max-height: none');
    expect(ruleFor('.avs-backdrop')).toContain('position: static');
    expect(ruleFor('.vss-section')).toContain('break-inside: avoid');
  });

  it('a11y smoke: the open dialog has no serious or critical axe violation', async () => {
    const fixture = render({
      ...summary,
      signedByName: 'Cdx Clinician',
      signedAt: '2026-11-07T12:10:00',
      vitalSigns: {
        systolicBpMmHg: 142,
        diastolicBpMmHg: 88,
        heartRateBpm: 72,
        flagged: false,
      },
      allergies: [{ allergen: 'Cdx allergen' }],
      currentMedications: [
        { name: 'Cdx drug', dosage: '1 g', drugKey: 'd' },
        { name: 'Cdx drug', dosage: '500 mg', drugKey: 'd' },
      ],
      hospitalPhone: '5550100',
    });
    document.body.appendChild(fixture.nativeElement);
    try {
      const results = await axe.run(fixture.nativeElement as HTMLElement, {
        resultTypes: ['violations'],
      });
      const serious = results.violations.filter(
        (v) => v.impact === 'serious' || v.impact === 'critical',
      );
      expect(serious.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
    } finally {
      (fixture.nativeElement as HTMLElement).remove();
    }
  });
});
