import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { DischargeComponent } from './discharge';
import { DischargeSummary } from '../services/discharge.service';
import { SYSTEM_FOLLOW_UP_TYPE_KEYS } from './follow-up-type';
import fr from '../../assets/i18n/fr.json';

/**
 * QA D3152 (r70a): « Voir » showed « DISCHARGE_FOLLOW_UP — (29 nov. 2026) »: the
 * server's code, and not the purpose the doctor typed in the discharge dialog.
 */
describe('DischargeComponent — r70a (D3152: the follow-up in words, with its purpose)', () => {
  function render(followUps: DischargeSummary['followUpAppointments']): HTMLElement {
    TestBed.configureTestingModule({
      imports: [DischargeComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    const fixture = TestBed.createComponent(DischargeComponent);
    const component = fixture.componentInstance;
    component.viewedSummary.set({
      id: 's1',
      followUpAppointments: followUps,
    } as unknown as DischargeSummary);
    component.showViewModal.set(true);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const items = (host: HTMLElement): string[] =>
    Array.from(host.querySelectorAll('[data-testid="discharge-view-follow-up"]')).map((e) =>
      (e.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );

  it('shows the dialog follow-up as « Suivi », with its purpose and date, never the code', () => {
    const host = render([
      {
        appointmentType: 'DISCHARGE_FOLLOW_UP',
        appointmentDate: '2026-11-29',
        purpose: 'Contrôle post-paludisme',
      },
    ]);
    const [line] = items(host);
    expect(line).toContain('Suivi — Contrôle post-paludisme');
    expect(line).toContain('29');
    expect(line).not.toContain('DISCHARGE_FOLLOW_UP');
    // No dangling dash for a provider that is not there.
    expect(line).not.toMatch(/—\s*\(/);
  });

  it('shows the checkout follow-up in words, and a typed type as written, with its provider', () => {
    const host = render([
      { appointmentType: 'FOLLOW_UP', purpose: 'Revoir les résultats' },
      { appointmentType: 'Cardiologie', providerName: 'Dr Kaboré' },
    ]);
    const [checkout, typed] = items(host);
    expect(checkout).toBe('Suivi — Revoir les résultats');
    expect(typed).toBe('Cardiologie · Dr Kaboré');
  });

  it('every server follow-up type has its words in the three languages', () => {
    for (const key of Object.values(SYSTEM_FOLLOW_UP_TYPE_KEYS)) {
      const [ns, name] = key.split('.');
      expect((fr as Record<string, Record<string, unknown>>)[ns][name]).toBe('Suivi');
    }
  });
});
