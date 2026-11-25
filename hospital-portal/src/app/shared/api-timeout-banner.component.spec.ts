import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ApiTimeoutBannerComponent } from './api-timeout-banner.component';
import { ApiTimeoutService } from '../core/api-timeout.service';
import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

describe('D3602 shared API timeout notice', () => {
  let fixture: ComponentFixture<ApiTimeoutBannerComponent>;
  let notice: ApiTimeoutService;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ApiTimeoutBannerComponent, TranslateModule.forRoot()],
    });
    notice = TestBed.inject(ApiTimeoutService);
    fixture = TestBed.createComponent(ApiTimeoutBannerComponent);
  });
  for (const [lang, translations] of Object.entries({ en, fr, es })) {
    it(`never claims a read may have saved data in ${lang}`, () => {
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation(lang, translations);
      translate.use(lang).subscribe();
      notice.noteTimeout('GET');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[data-testid="api-timeout-write"]')).toBeNull();
      expect(fixture.nativeElement.textContent).not.toContain(
        translations.API_TIMEOUT.WRITE_UNKNOWN,
      );
    });
    it(`shows a translated timeout and deliberate reload in ${lang}`, () => {
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation(lang, translations);
      translate.use(lang);
      notice.noteTimeout('POST');
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      const banner = el.querySelector('[role="alert"]')!;
      expect(banner.textContent).toContain(translations.API_TIMEOUT.TITLE);
      expect(banner.textContent).toContain(translations.API_TIMEOUT.WRITE_UNKNOWN);
      expect(banner.textContent).toContain(translations.API_TIMEOUT.KEEP_INPUT);
      const reload = spyOn(fixture.componentInstance, 'reload');
      (el.querySelector('[data-testid="api-timeout-retry"]') as HTMLButtonElement).click();
      expect(reload).toHaveBeenCalledTimes(1);
      expect(el.textContent).not.toContain('API_TIMEOUT.');
      (el.querySelector('button:last-child') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(el.querySelector('[role="alert"]')).toBeNull();
    });
  }
});
