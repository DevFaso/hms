import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, of, throwError } from 'rxjs';

import { OrganisationTotalsComponent } from './organisation-totals.component';
import {
  HospitalService,
  OrganisationHospitalTotals,
  OrganisationSummary,
} from '../../services/hospital.service';
import fr from '../../../assets/i18n/fr.json';

/**
 * Platform admin rules 2026-11-20, rule 1b: the organisation admin reads
 * totals per hospital, never a person, and a small sensitive count is not shown.
 */
describe('OrganisationTotalsComponent — totals only, small counts withheld (platform admin rules, rule 1b)', () => {
  const hospital = (
    over: Partial<OrganisationHospitalTotals> = {},
  ): OrganisationHospitalTotals => ({
    hospitalId: 'h-a',
    hospitalName: 'Clinique Test A',
    currencyCode: 'XOF',
    activeStaff: 46,
    appointments: 1250,
    admissions: 9,
    invoicedTotal: 250000,
    collectedTotal: 12500,
    deaths: { value: 0, suppressed: false },
    ...over,
  });

  const summary = (hospitals: OrganisationHospitalTotals[]): OrganisationSummary => ({
    from: '2026-11-01',
    to: '2026-11-20',
    smallCountThreshold: 5,
    hospitals,
  });

  let answer: () => Observable<OrganisationSummary>;
  let calls: number;

  function render(): ComponentFixture<OrganisationTotalsComponent> {
    calls = 0;
    TestBed.configureTestingModule({
      imports: [OrganisationTotalsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: HospitalService,
          useValue: {
            organisationSummary: () => {
              calls += 1;
              return answer();
            },
          },
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr as never);
    translate.use('fr');
    const fixture = TestBed.createComponent(OrganisationTotalsComponent);
    fixture.detectChanges();
    return fixture;
  }

  const text = (fixture: ComponentFixture<unknown>) =>
    ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
  const all = (fixture: ComponentFixture<unknown>, selector: string) =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(selector));

  afterEach(() => TestBed.resetTestingModule());

  it('prints one block per hospital: counts, and amounts as whole francs with the currency code', () => {
    answer = () =>
      of(
        summary([
          hospital(),
          hospital({
            hospitalId: 'h-b',
            hospitalName: 'Clinique Test B',
            currencyCode: 'XAF',
            invoicedTotal: 4000,
            collectedTotal: 0,
          }),
        ]),
      );
    const fixture = render();

    const rows = all(fixture, '[data-testid="organisation-totals-row"]');
    expect(rows.length).toBe(2);
    // Raw text: the amounts carry no-break spaces, which a whitespace squeeze would flatten.
    const first = rows[0].textContent ?? '';
    expect(first).toContain('Clinique Test A');
    expect(first).toContain('XOF');
    // Whole francs, grouped the French way, never a decimal.
    expect(first).toContain('250 000 F CFA');
    expect(first).toContain('12 500 F CFA');
    expect(first).not.toMatch(/\d[.,]\d{2}\s*F/);
    expect((rows[1].textContent ?? '').replace(/\s+/g, ' ')).toContain('XAF');
    expect(text(fixture)).toContain('Chiffres de l’organisation');
    expect(text(fixture)).toContain('aucune liste de patients');
  });

  it('a withheld count reads « Moins de 5 », and no number stands in its place', () => {
    answer = () => of(summary([hospital({ deaths: { value: null, suppressed: true } })]));
    const fixture = render();

    const withheld = all(fixture, '[data-testid="organisation-totals-withheld"]');
    expect(withheld.length).toBe(1);
    expect((withheld[0].textContent ?? '').trim()).toBe('Moins de 5');
    expect(text(fixture)).toContain('inférieur à 5');
  });

  it('zero and a count at the threshold are printed as numbers', () => {
    answer = () =>
      of(
        summary([
          hospital({ deaths: { value: 0, suppressed: false } }),
          hospital({ hospitalId: 'h-b', deaths: { value: 7, suppressed: false } }),
        ]),
      );
    const fixture = render();

    expect(all(fixture, '[data-testid="organisation-totals-withheld"]').length).toBe(0);
    const deaths = all(fixture, '.ot-figure:last-child dd').map((cell) =>
      (cell.textContent ?? '').trim(),
    );
    expect(deaths).toEqual(['0', '7']);
  });

  it('a failed load says so and offers to try again; nothing looks like a zero', () => {
    let fail = true;
    answer = () => (fail ? throwError(() => new Error('down')) : of(summary([hospital()])));
    const fixture = render();

    const failed = all(fixture, '[data-testid="organisation-totals-failed"]');
    expect(failed.length).toBe(1);
    expect(failed[0].getAttribute('role')).toBe('alert');
    expect(all(fixture, '[data-testid="organisation-totals-row"]').length).toBe(0);

    fail = false;
    failed[0].querySelector('button')!.click();
    fixture.detectChanges();
    expect(calls).toBe(2);
    expect(all(fixture, '[data-testid="organisation-totals-row"]').length).toBe(1);
  });

  it('an organisation with no hospital yet says so', () => {
    answer = () => of(summary([]));
    const fixture = render();

    expect(text(fixture)).toContain('Aucun hôpital dans votre organisation');
  });

  for (const width of [320, 390, 768, 1280]) {
    it(`fits a ${width}px page with a long hospital name and large amounts`, () => {
      answer = () =>
        of(
          summary([
            hospital({
              hospitalName:
                'Centre Hospitalier Universitaire Régional de Démonstration-Intercommunale-Nord',
              invoicedTotal: 9876543210,
              collectedTotal: 9876543210,
              appointments: 123456789,
            }),
          ]),
        );
      const fixture = render();
      const host = fixture.nativeElement as HTMLElement;
      host.style.display = 'block';
      host.style.width = `${width}px`;
      document.body.appendChild(host);
      try {
        fixture.detectChanges();
        const card = host.querySelector<HTMLElement>('.ot-card')!;
        expect(card.scrollWidth).toBeLessThanOrEqual(card.clientWidth + 1);
        for (const cell of all(fixture, '.ot-figure, .ot-hospital')) {
          expect(cell.getBoundingClientRect().right)
            .withContext(cell.className)
            .toBeLessThanOrEqual(card.getBoundingClientRect().right + 1);
        }
      } finally {
        host.remove();
      }
    });
  }
});
