import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ImagingCatalogueComponent } from './imaging-catalogue.component';
import {
  ImagingCatalogueExam,
  ImagingCatalogueService,
} from '../../services/imaging-catalogue.service';
import { ClinicalRulesService } from '../../services/clinical-rules.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { closeScreens, openScreen } from '../../testing/screen-frame';
import fr from '../../../assets/i18n/fr.json';

/**
 * QA D3450 (r70, High): the hospital admin and the radiologist say, per exam of the imaging
 * catalogue, whether it may reveal a pregnancy; the screen says what each answer means for a
 * family proxy, « not decided » included.
 */
describe('ImagingCatalogueComponent (D3450: « peut révéler une grossesse »)', () => {
  let api: jasmine.SpyObj<ImagingCatalogueService>;
  let toast: jasmine.SpyObj<ToastService>;

  const exam = (over: Partial<ImagingCatalogueExam> = {}): ImagingCatalogueExam => ({
    id: 'e1',
    code: 'RX-THORAX',
    label: 'Radiographie du thorax (face)',
    modality: 'XRAY',
    bodyRegion: null,
    mayRevealPregnancy: null,
    namedAsPregnancyStudy: false,
    pregnancyFlagDecidedBy: null,
    pregnancyFlagDecidedAt: null,
    canEdit: true,
    ...over,
  });

  function render(exams: ImagingCatalogueExam[]): ComponentFixture<ImagingCatalogueComponent> {
    api.list.and.returnValue(of(exams));
    TestBed.configureTestingModule({
      imports: [ImagingCatalogueComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ImagingCatalogueService, useValue: api },
        { provide: ToastService, useValue: toast },
        // V356: the « provisoire » label reads the acting hospital; none here, the screen's wording stays.
        { provide: RoleContextService, useValue: { effectiveHospitalIdForRequest: () => null } },
        {
          provide: ClinicalRulesService,
          useValue: {
            overviewSignal: () => null,
            statusAt: () => null,
            ensureLoaded: () => undefined,
          },
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr as never);
    translate.use('fr');
    const fixture = TestBed.createComponent(ImagingCatalogueComponent);
    fixture.detectChanges();
    return fixture;
  }

  const el = <T extends HTMLElement>(fixture: ComponentFixture<unknown>, testid: string): T =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(`[data-testid="${testid}"]`)!;

  function choose(fixture: ComponentFixture<unknown>, testid: string, value: string): void {
    const select = el<HTMLSelectElement>(fixture, testid);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    api = jasmine.createSpyObj('ImagingCatalogueService', ['list', 'update']);
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
  });

  afterEach(closeScreens);

  it('says the rule is provisional and what « not decided » means: named exams are hidden, the others held back by prudence', async () => {
    const fixture = render([
      exam(),
      exam({
        id: 'e2',
        code: 'ECHO-OBS',
        label: 'Échographie obstétricale',
        modality: 'ULTRASOUND',
        namedAsPregnancyStudy: true,
      }),
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture, 'imaging-catalogue-rule').textContent).toContain('provisoire');
    expect(el(fixture, 'catalogue-status-RX-THORAX').textContent).toContain('par prudence');
    expect(el(fixture, 'catalogue-status-ECHO-OBS').textContent).toContain(
      'obstétrical, pelvien ou gynécologique',
    );
    expect(el<HTMLSelectElement>(fixture, 'catalogue-flag-RX-THORAX').value).toBe('UNDECIDED');
  });

  it('clears an exam: sends false with the body region, shows who decided, and the save is offered only for a change', async () => {
    const fixture = render([exam()]);
    const save = el<HTMLButtonElement>(fixture, 'catalogue-save-RX-THORAX');
    expect(save.disabled).withContext('nothing changed yet').toBeTrue();

    api.update.and.returnValue(
      of(
        exam({
          mayRevealPregnancy: false,
          bodyRegion: 'Thorax',
          pregnancyFlagDecidedBy: 'Dr Radiologue',
          pregnancyFlagDecidedAt: '2026-11-22T10:00:00',
        }),
      ),
    );
    choose(fixture, 'catalogue-flag-RX-THORAX', 'NO');
    const region = el<HTMLInputElement>(fixture, 'catalogue-region-RX-THORAX');
    region.value = ' Thorax ';
    region.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(save.disabled).toBeFalse();
    save.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.update).toHaveBeenCalledOnceWith('e1', {
      mayRevealPregnancy: false,
      bodyRegion: 'Thorax',
    });
    expect(toast.success).toHaveBeenCalled();
    const status = el(fixture, 'catalogue-status-RX-THORAX').textContent ?? '';
    expect(status).toContain('Montré aux proches');
    expect(status).toContain('Dr Radiologue');
    expect(el<HTMLButtonElement>(fixture, 'catalogue-save-RX-THORAX').disabled).toBeTrue();
  });

  it('flags an exam (true) and puts one back to « not decided » (null)', () => {
    const fixture = render([
      exam(),
      exam({ id: 'e2', code: 'ECHO-ABDO', mayRevealPregnancy: false }),
    ]);
    api.update.and.callFake((id, body) => of(exam({ id, ...body })));

    choose(fixture, 'catalogue-flag-RX-THORAX', 'YES');
    el<HTMLButtonElement>(fixture, 'catalogue-save-RX-THORAX').click();
    expect(api.update.calls.mostRecent().args).toEqual([
      'e1',
      { mayRevealPregnancy: true, bodyRegion: null },
    ]);

    choose(fixture, 'catalogue-flag-ECHO-ABDO', 'UNDECIDED');
    el<HTMLButtonElement>(fixture, 'catalogue-save-ECHO-ABDO').click();
    expect(api.update.calls.mostRecent().args).toEqual([
      'e2',
      { mayRevealPregnancy: null, bodyRegion: null },
    ]);
  });

  it('a reader who cannot edit (a doctor) sees the answers, disabled, and no save', async () => {
    const fixture = render([exam({ canEdit: false, mayRevealPregnancy: true })]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el<HTMLSelectElement>(fixture, 'catalogue-flag-RX-THORAX').disabled).toBeTrue();
    expect(el<HTMLInputElement>(fixture, 'catalogue-region-RX-THORAX').disabled).toBeTrue();
    expect(el(fixture, 'catalogue-save-RX-THORAX')).toBeNull();
    expect(el(fixture, 'catalogue-status-RX-THORAX').textContent).toContain('Jamais montré');
  });

  it("shows the server's refusal when the save is refused, and keeps the reader's choice", () => {
    const fixture = render([exam()]);
    api.update.and.returnValue(
      throwError(() => ({
        status: 403,
        error: { message: 'Seuls un administrateur ou un radiologue' },
      })),
    );
    choose(fixture, 'catalogue-flag-RX-THORAX', 'NO');
    el<HTMLButtonElement>(fixture, 'catalogue-save-RX-THORAX').click();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith('Seuls un administrateur ou un radiologue');
    expect(el<HTMLSelectElement>(fixture, 'catalogue-flag-RX-THORAX').value).toBe('NO');
  });

  it('says so when the catalogue is empty or cannot be read, and retries', () => {
    const empty = render([]);
    expect(el(empty, 'imaging-catalogue-empty').textContent).toContain('grille tarifaire');

    TestBed.resetTestingModule();
    api.list.and.returnValue(throwError(() => ({ status: 500 })));
    TestBed.configureTestingModule({
      imports: [ImagingCatalogueComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ImagingCatalogueService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: { effectiveHospitalIdForRequest: () => null } },
        {
          provide: ClinicalRulesService,
          useValue: {
            overviewSignal: () => null,
            statusAt: () => null,
            ensureLoaded: () => undefined,
          },
        },
      ],
    });
    const failed = TestBed.createComponent(ImagingCatalogueComponent);
    failed.detectChanges();
    expect(el(failed, 'imaging-catalogue-failed')).not.toBeNull();
    api.list.and.returnValue(of([exam()]));
    failed.componentInstance.load();
    failed.detectChanges();
    expect(el(failed, 'catalogue-exam-RX-THORAX')).not.toBeNull();
  });

  it('fits at 1366, 1024, 768 and 390 px wide: no exam, field or status runs past the page', async () => {
    const long =
      'Échographie obstétricale morphologique du deuxième trimestre avec doppler '.repeat(2);
    const fixture = render([
      exam({ label: long, code: 'ECHO-OBS-MORPHO-T2-DOPPLER', namedAsPregnancyStudy: true }),
      exam({
        id: 'e2',
        mayRevealPregnancy: true,
        pregnancyFlagDecidedBy: 'Dr Aminata Ouédraogo-Sawadogo',
        pregnancyFlagDecidedAt: '2026-11-22T10:00:00',
      }),
    ]);
    for (const width of [1366, 1024, 768, 390]) {
      const screen = await openScreen(width);
      const host = screen.createElement('div');
      // The page's content box: the screen less the sidebar on a laptop, less the gutters on a phone.
      host.style.width = `${width >= 1024 ? width - 310 : width - 32}px`;
      screen.body.appendChild(host);
      host.appendChild(fixture.nativeElement);
      const edge = host.getBoundingClientRect().right;
      const parts = Array.from(
        host.querySelectorAll<HTMLElement>(
          '.img-catalogue__exam, .img-catalogue__name, .img-catalogue__field, .img-catalogue__status, input, select, button',
        ),
      );
      expect(parts.length).toBeGreaterThan(8);
      for (const part of parts) {
        expect(part.getBoundingClientRect().right)
          .withContext(`${width}px: ${part.className || part.tagName}`)
          .toBeLessThanOrEqual(edge + 1);
      }
      expect(host.scrollWidth)
        .withContext(`${width}px: page`)
        .toBeLessThanOrEqual(host.clientWidth + 1);
    }
  });
});
