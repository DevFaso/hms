import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import { AdolescentConfidentialityAge } from '../../services/sensitive-policy.service';
import { AdolescentAgeSettingComponent } from './adolescent-age-setting';
import fr from '../../../assets/i18n/fr.json';

@Component({
  standalone: true,
  imports: [AdolescentAgeSettingComponent],
  template: `
    <div class="host" [style.width.px]="width()">
      <app-adolescent-age-setting hospitalId="h-1" />
    </div>
  `,
})
class HostComponent {
  width = signal(1366);
}

const URL = '/hospitals/h-1/adolescent-confidentiality-age';

function setting(configuredAge: number | null): AdolescentConfidentialityAge {
  return {
    hospitalId: 'h-1',
    configuredAge,
    effectiveAge: configuredAge ?? 12,
    defaultAge: 12,
    provisional: true,
  };
}

/**
 * Policy 2026-11-20 (provisional): the hospital's adolescent confidentiality
 * age. The default of 12 awaits a jurist, and the card says so.
 */
describe('AdolescentAgeSettingComponent (policy 2026-11-20)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;
  let httpMock: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [HostComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  const q = <T extends HTMLElement>(testId: string) =>
    host.querySelector<T>(`[data-testid="${testId}"]`);

  function load(current: AdolescentConfidentialityAge): void {
    httpMock.expectOne(URL).flush(current);
    fixture.detectChanges();
  }

  async function enter(value: string): Promise<void> {
    const input = q<HTMLInputElement>('adolescent-age-input')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('shows the provisional default of 12, and says a jurist must confirm it', async () => {
    load(setting(null));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(q('adolescent-age-provisional')!.textContent).toContain('provisoire');
    expect(q('adolescent-age-provisional')!.textContent).toContain('juriste');
    expect(q('adolescent-age-state')!.textContent).toContain('par défaut');
    expect(q<HTMLInputElement>('adolescent-age-input')!.value).toBe('12');
    expect(q('adolescent-age-reset')).toBeNull();
    expect(q<HTMLButtonElement>('adolescent-age-save')!.disabled).toBeTrue();
  });

  it('saves the hospital’s own age, then offers the way back to the default', async () => {
    load(setting(null));
    await enter('14');

    q<HTMLButtonElement>('adolescent-age-save')!.click();
    const save = httpMock.expectOne(URL);
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ age: 14 });
    save.flush(setting(14));
    fixture.detectChanges();

    expect(toast.success).toHaveBeenCalled();
    expect(q('adolescent-age-state')!.textContent).toContain('14');
    q<HTMLButtonElement>('adolescent-age-reset')!.click();
    const reset = httpMock.expectOne(URL);
    expect(reset.request.body).toEqual({ age: null });
    reset.flush(setting(null));
  });

  it('refuses an age outside 0 to 18 before any request', async () => {
    load(setting(null));
    await enter('19');
    expect(q<HTMLButtonElement>('adolescent-age-save')!.disabled).toBeTrue();
    await enter('');
    expect(q<HTMLButtonElement>('adolescent-age-save')!.disabled).toBeTrue();
    httpMock.expectNone(URL);
  });

  it('shows the server refusal and keeps the stored value', async () => {
    load(setting(null));
    await enter('15');
    q<HTMLButtonElement>('adolescent-age-save')!.click();
    httpMock.expectOne(URL).flush({ message: 'Refusé' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith('Refusé');
    expect(q('adolescent-age-state')!.textContent).toContain('12');
  });

  it('is not shown to a caller the server does not let read it', () => {
    httpMock.expectOne(URL).flush('no', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(q('adolescent-age')).toBeNull();
  });

  for (const width of [1366, 1024, 768, 390]) {
    it(`never spills past a ${width}px container`, () => {
      fixture.componentInstance.width.set(width);
      fixture.detectChanges();
      load(setting(14));

      const box = host.querySelector<HTMLElement>('.host')!;
      expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1);
      const right = box.getBoundingClientRect().right;
      for (const part of Array.from(
        box.querySelectorAll<HTMLElement>('p, label, button, input, h2'),
      )) {
        expect(part.getBoundingClientRect().right).toBeLessThanOrEqual(right + 1);
      }
    });
  }
});
