import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { SensitivityCategory } from './sensitivity-category';
import { SensitivitySelectComponent } from './sensitivity-select';

/** The French the page ships, long enough to prove the wrapping. */
const FR = {
  SENSITIVITY_TAG: {
    LABEL: 'Catégorie sensible',
    NONE: 'Aucune',
    CATEGORY_SUBSTANCE_USE: 'Addictologie',
    CATEGORY_BEHAVIOURAL_HEALTH: 'Santé mentale',
    CATEGORY_HIV: 'VIH',
    CATEGORY_REPRODUCTIVE_HEALTH: 'Santé reproductive',
    CATEGORY_STI: 'Infections sexuellement transmissibles',
    CATEGORY_VIRAL_HEPATITIS: 'Hépatites virales B et C',
    CATEGORY_PREGNANCY: 'Grossesse',
    CATEGORY_SEXUAL_DOMESTIC_VIOLENCE: 'Violences sexuelles et domestiques',
    CATEGORY_GENETIC: 'Tests génétiques',
    CATEGORY_SICKLE_CELL: 'Drépanocytose (génotype)',
    CATEGORY_TUBERCULOSIS: 'Tuberculose (accès restreint)',
    INHERITED: 'S’applique actuellement : {{category}} (hérité de la visite ou de son service)',
    ENCOUNTER_HINT:
      'Les résultats, médicaments, diagnostics et factures d’une visite classée ne sont pas montrés à une ' +
      'personne disposant d’un accès famille, et restent derrière le bris de glace pour les autres ' +
      'établissements. Le patient continue de tout voir.',
  },
};

@Component({
  standalone: true,
  imports: [SensitivitySelectComponent],
  template: `
    <div class="host" [style.width.px]="width()">
      <app-sensitivity-select
        controlId="tag"
        hintKey="SENSITIVITY_TAG.ENCOUNTER_HINT"
        [value]="value()"
        [inherited]="inherited()"
        [disabled]="disabled()"
        (picked)="picked.push($event)"
      />
    </div>
  `,
})
class HostComponent {
  width = signal(600);
  value = signal<SensitivityCategory | null>(null);
  inherited = signal<SensitivityCategory | null>(null);
  disabled = signal(false);
  picked: (SensitivityCategory | null)[] = [];
}

describe('SensitivitySelectComponent (QA D2730)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HostComponent, TranslateModule.forRoot()],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', FR);
    translate.use('fr');
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  });

  const select = () => host.querySelector<HTMLSelectElement>('#tag')!;

  it('offers "none" and every category of the 2026-11-20 policy, labelled, with the hint tied to the control', () => {
    const options = Array.from(select().options).map((o) => o.textContent?.trim());
    expect(options).toEqual([
      'Aucune',
      'Addictologie',
      'Santé mentale',
      'VIH',
      'Santé reproductive',
      'Infections sexuellement transmissibles',
      'Hépatites virales B et C',
      'Grossesse',
      'Violences sexuelles et domestiques',
      'Tests génétiques',
      'Drépanocytose (génotype)',
      'Tuberculose (accès restreint)',
    ]);
    expect(host.querySelector('label')!.getAttribute('for')).toBe('tag');
    expect(select().getAttribute('aria-describedby')).toBe('tag-hint');
    expect(host.querySelector('#tag-hint')!.textContent).toContain('accès famille');
  });

  it('shows the stored category, and reports a choice without changing it itself', () => {
    fixture.componentInstance.value.set('HIV');
    fixture.detectChanges();
    expect(select().value).toBe('HIV');

    select().value = 'REPRODUCTIVE_HEALTH';
    select().dispatchEvent(new Event('change'));
    select().value = '';
    select().dispatchEvent(new Event('change'));

    expect(fixture.componentInstance.picked).toEqual(['REPRODUCTIVE_HEALTH', null]);
  });

  it('says which category applies when the row has none of its own', () => {
    expect(host.querySelector('[data-testid="tag-inherited"]')).toBeNull();
    fixture.componentInstance.inherited.set('BEHAVIOURAL_HEALTH');
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="tag-inherited"]')!.textContent).toContain(
      'Santé mentale',
    );

    // Its own tag wins: the inherited line goes.
    fixture.componentInstance.value.set('HIV');
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="tag-inherited"]')).toBeNull();
  });

  it('is disabled while the host saves', () => {
    fixture.componentInstance.disabled.set(true);
    fixture.detectChanges();
    expect(select().disabled).toBeTrue();
  });

  // The control sits in a visit's detail panel, a dialog and a form column:
  // their widths at 1366, 1024, 768 and 390 px screens, down to a phone's gutter.
  for (const width of [1366, 1024, 768, 390, 300]) {
    it(`never spills past a ${width}px container`, () => {
      fixture.componentInstance.width.set(width);
      // The longest label of the list.
      fixture.componentInstance.inherited.set('STI');
      fixture.detectChanges();
      const box = host.querySelector<HTMLElement>('.host')!;
      expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1);
      for (const part of Array.from(box.querySelectorAll<HTMLElement>('label, select, p'))) {
        expect(part.getBoundingClientRect().right).toBeLessThanOrEqual(
          box.getBoundingClientRect().right + 1,
        );
      }
    });
  }
});
