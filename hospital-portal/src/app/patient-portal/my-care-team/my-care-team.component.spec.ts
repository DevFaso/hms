import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyCareTeamComponent } from './my-care-team.component';
import {
  CareTeamDTO,
  PatientPortalService,
  PrimaryCareEntry,
} from '../../services/patient-portal.service';

/**
 * The care team as GET /me/patient/care-team returns it: CareTeamDTO
 * { primaryCare, primaryCareHistory }. The page used to read `members`, which
 * the endpoint never sends, so it always showed its empty state. Asserted
 * through the DOM in French (the #661 shape).
 */
describe('MyCareTeamComponent', () => {
  let fixture: ComponentFixture<MyCareTeamComponent>;
  let portal: jasmine.SpyObj<PatientPortalService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');

  function entry(overrides: Partial<PrimaryCareEntry> = {}): PrimaryCareEntry {
    return {
      id: 'pcp-1',
      hospitalId: 'h-1',
      hospitalName: 'CHU Yalgado',
      doctorUserId: 'd-1',
      doctorDisplay: 'Dr Awa Traoré',
      startDate: '2026-01-15',
      endDate: null,
      current: true,
      ...overrides,
    };
  }

  function create(team: CareTeamDTO): void {
    portal.getMyCareTeam.and.returnValue(of(team));
    fixture = TestBed.createComponent(MyCareTeamComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', ['getMyCareTeam']);
    TestBed.configureTestingModule({
      imports: [MyCareTeamComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: portal }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        CARE_TEAM: {
          EMPTY_TITLE: 'Aucun membre de l’équipe',
          PRIMARY_PROVIDER: 'Médecin traitant',
          SINCE: 'Depuis le {{date}}',
          HISTORY_TITLE: 'Médecins traitants précédents',
          LOAD_ERROR: 'Impossible de charger votre équipe soignante. Réessayez.',
        },
      },
    });
  });

  it('shows the current primary care provider with hospital and start date', () => {
    const current = entry();
    create({ primaryCare: current, primaryCareHistory: [current] });

    const card = host().querySelector('[data-testid="care-team-current"]')!;
    expect(card.querySelector('.ct-badge')?.textContent?.trim()).toBe('Médecin traitant');
    expect(card.querySelector('.ct-name')?.textContent?.trim()).toBe('Dr Awa Traoré');
    expect(card.querySelector('.ct-role')?.textContent?.trim()).toBe('CHU Yalgado');
    expect(card.querySelector('.ct-contact')?.textContent?.trim()).toMatch(/^Depuis le /);
    // The history repeats the current link; it must not be listed twice.
    expect(host().querySelector('[data-testid="care-team-history"]')).toBeNull();
  });

  it('lists the earlier providers with their dates under a translated heading', () => {
    const current = entry();
    const earlier = entry({
      id: 'pcp-0',
      doctorDisplay: 'Dr Idrissa Sawadogo',
      hospitalName: 'CMA Pissy',
      startDate: '2024-03-01',
      endDate: '2026-01-14',
      current: false,
    });
    create({ primaryCare: current, primaryCareHistory: [current, earlier] });

    const history = host().querySelector('[data-testid="care-team-history"]')!;
    expect(history.querySelector('h2')?.textContent?.trim()).toBe('Médecins traitants précédents');
    expect(texts('[data-testid="care-team-history"] .ct-name')).toEqual(['Dr Idrissa Sawadogo']);
    expect(texts('[data-testid="care-team-history"] .ct-role')).toEqual(['CMA Pissy']);
    const period = history.querySelector('.ct-contact')?.textContent ?? '';
    expect(period).toContain('2024');
    expect(period).toContain('2026');
  });

  it('shows the history alone when there is no current provider', () => {
    create({
      primaryCare: null,
      primaryCareHistory: [entry({ id: 'pcp-0', current: false, endDate: '2026-01-14' })],
    });
    expect(host().querySelector('[data-testid="care-team-current"]')).toBeNull();
    expect(texts('[data-testid="care-team-history"] .ct-name')).toEqual(['Dr Awa Traoré']);
  });

  it('shows the translated empty state when there is no primary care on file', () => {
    create({ primaryCare: null, primaryCareHistory: [] });
    expect(texts('[data-testid="care-team-empty"] h3')).toEqual(['Aucun membre de l’équipe']);
  });

  it('shows an error, not an empty team, when the read fails', () => {
    portal.getMyCareTeam.and.returnValue(throwError(() => new Error('500')));
    fixture = TestBed.createComponent(MyCareTeamComponent);
    fixture.detectChanges();
    expect(texts('[data-testid="care-team-error"] p')).toEqual([
      'Impossible de charger votre équipe soignante. Réessayez.',
    ]);
    expect(host().querySelector('[data-testid="care-team-empty"]')).toBeNull();
  });
});
