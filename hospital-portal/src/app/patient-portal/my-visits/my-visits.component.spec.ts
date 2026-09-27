import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyVisitsComponent } from './my-visits.component';
import { PatientPortalService, PortalEncounter } from '../../services/patient-portal.service';

/**
 * The patient's visit history. Asserted through the rendered DOM in French
 * (the #661 shape): the fallbacks for a missing department or provider are
 * translation keys, and only the DOM shows whether they resolve.
 */
describe('MyVisitsComponent', () => {
  let fixture: ComponentFixture<MyVisitsComponent>;
  let portal: jasmine.SpyObj<PatientPortalService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');

  function visit(overrides: Partial<PortalEncounter> = {}): PortalEncounter {
    return {
      id: 'v1',
      date: '2026-09-01T09:00:00',
      type: '',
      providerName: 'Dr Awa Traoré',
      department: 'Cardiologie',
      chiefComplaint: '',
      diagnosisSummary: '',
      status: '',
      notes: '',
      hospitalName: 'CHU Yalgado',
      appointmentReason: '',
      ...overrides,
    };
  }

  function create(visits: PortalEncounter[]): void {
    portal.getMyEncounters.and.returnValue(of(visits));
    fixture = TestBed.createComponent(MyVisitsComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyEncounters',
    ]);
    TestBed.configureTestingModule({
      imports: [MyVisitsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: portal }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        VISITS: {
          TITLE: 'Mes visites',
          EMPTY_TITLE: 'Aucune visite',
          GENERAL: 'Général',
          PROVIDER: 'Soignant',
          NOT_ASSIGNED: 'Non attribué',
          DEPARTMENT: 'Service',
          CHIEF_COMPLAINT: 'Motif',
        },
      },
    });
  });

  it('shows the translated empty state when there is no visit', () => {
    create([]);
    expect(texts('h1')[0]).toContain('Mes visites');
    expect(texts('.portal-empty h3')).toEqual(['Aucune visite']);
  });

  it('lists each visit with its department and provider', () => {
    create([visit()]);
    expect(texts('.pli-title')[0]).toContain('Cardiologie');
    expect(texts('.pli-sub')).toEqual(['Dr Awa Traoré']);
    expect(host().querySelector('.detail-panel')).toBeNull();
  });

  it('falls back to translated words when the department or provider is missing', () => {
    create([visit({ department: '', providerName: '' })]);
    expect(texts('.pli-title')[0]).toContain('Général');
    expect(texts('.pli-sub')).toEqual(['Soignant']);

    (host().querySelector('.portal-list-item') as HTMLElement).click();
    fixture.detectChanges();
    const values = texts('.detail-panel .dv');
    expect(values).toContain('Non attribué');
    expect(values).toContain('Général');
  });

  it('opens the detail panel on click, with the complaint only when there is one', () => {
    create([visit({ chiefComplaint: 'complaint-a' })]);
    (host().querySelector('.portal-list-item') as HTMLElement).click();
    fixture.detectChanges();
    expect(texts('.detail-panel .dl')).toContain('Motif');
    expect(texts('.detail-panel .dv')).toContain('complaint-a');

    (host().querySelector('.portal-list-item') as HTMLElement).click();
    fixture.detectChanges();
    expect(host().querySelector('.detail-panel')).toBeNull();
  });

  it('opens from the keyboard', () => {
    create([visit()]);
    host()
      .querySelector('.portal-list-item')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(texts('.detail-panel .dl')).toContain('Service');
  });
});
