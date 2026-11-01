import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { LabCorrectionBadgeComponent } from './lab-correction-badge.component';

/**
 * QA D1167 / D1137 (round 21): the in-basket read "12,8 g/dLCorrigéBas" and
 * the portal dashboard "12,8 g/dLCorrigé (13,5 – 17,5 g/dL)". The shapes below
 * are the two templates as written (the badge right after a unit span, then a
 * severity or a range), compiled by Angular, whose whitespace removal drops the
 * line break between `</span>` and the badge.
 */
@Component({
  standalone: true,
  imports: [LabCorrectionBadgeComponent],
  template: `
    <div class="cell" id="inbasket">
      <span class="result-value">12,8 g/dL</span>
      <!-- QA D1016: a correction of a value already released -->
      <app-lab-correction-badge [corrected]="true" />
      <span class="severity">Bas</span>
    </div>
    <div class="cell" id="portal">
      <span class="lab-value"
        >{{ '12,8' }}
        <!-- QA D1095: the value is never shown without its unit -->
        @if (unit) {
          <span class="lab-unit">{{ unit }}</span>
        }
        <app-lab-correction-badge [corrected]="true" audience="patient" />
        @if (unit) {
          <span class="lab-range">&ngsp;(13,5 – 17,5 g/dL)</span>
        }
      </span>
    </div>
    <div class="cell" id="plain">
      <span class="result-value">12,8 g/dL</span>
      <app-lab-correction-badge [corrected]="false" />
      <span class="severity">Bas</span>
    </div>
  `,
})
class HostComponent {
  readonly unit = 'g/dL';
}

describe('LabCorrectionBadgeComponent spacing (QA D1167 / D1137)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent, TranslateModule.forRoot()] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', { LAB_RESULTS: { CORRECTED: 'Corrigé' } });
    translate.use('fr');
  });

  function text(id: string): string {
    const fixture = TestBed.createComponent(HostComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const out = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      `#${id}`,
    )!.innerText;
    (fixture.nativeElement as HTMLElement).remove();
    return out.replace(/\s+/g, ' ').trim();
  }

  it('the in-basket cell reads "12,8 g/dL Corrigé Bas"', () => {
    expect(text('inbasket')).toBe('12,8 g/dL Corrigé Bas');
  });

  it('the portal dashboard cell reads "g/dL Corrigé (13,5 – 17,5 g/dL)"', () => {
    expect(text('portal')).toBe('12,8 g/dL Corrigé (13,5 – 17,5 g/dL)');
  });

  it('an uncorrected result shows no badge', () => {
    expect(text('plain')).not.toContain('Corrigé');
  });
});
