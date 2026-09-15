import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { EnumLabelService } from './enum-label.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

/**
 * The three-tier lookup itself is covered by the pipe's own spec, which has
 * exercised it in all three locales since PR #261. What is new — and what this
 * covers — is that the vocabulary is now one injectable object, so a component
 * that needs a label in TypeScript gets the SAME answer as the template beside
 * it instead of standing up a private pipe instance with its own memo.
 */
describe('EnumLabelService', () => {
  let service: EnumLabelService;
  let translate: TranslateService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('en');
    translate.use('en');
    service = TestBed.inject(EnumLabelService);
  });

  it('reads PORTAL.ENUM.<GROUP>.<VALUE> for the current language', () => {
    translate.setTranslation('fr', { PORTAL: { ENUM: { ROLE: { DOCTOR: 'Médecin' } } } }, true);
    translate.use('fr');
    expect(service.transform('DOCTOR', 'role')).toBe('Médecin');
  });

  it('is the same instance the pipe delegates to', () => {
    translate.setTranslation('en', { PORTAL: { ENUM: { ROLE: { NURSE: 'Charge Nurse' } } } }, true);
    const pipe = TestBed.runInInjectionContext(() => new EnumLabelPipe());
    try {
      // The reference, not the answer: comparing two equal strings would pass
      // just as happily against a private per-pipe copy of the vocabulary,
      // which is the shape this extraction exists to remove.
      expect((pipe as unknown as { labels: EnumLabelService }).labels).toBe(service);
      expect(pipe.transform('NURSE', 'role')).toBe('Charge Nurse');
    } finally {
      pipe.ngOnDestroy();
    }
  });

  it('never returns a cached label from another language', () => {
    translate.setTranslation('en', { PORTAL: { ENUM: { ROLE: { MIDWIFE: 'Midwife' } } } }, true);
    translate.setTranslation('fr', { PORTAL: { ENUM: { ROLE: { MIDWIFE: 'Sage-femme' } } } }, true);

    expect(service.transform('MIDWIFE', 'role')).toBe('Midwife');
    translate.use('fr');
    expect(service.transform('MIDWIFE', 'role')).toBe('Sage-femme');
    translate.use('en');
    expect(service.transform('MIDWIFE', 'role')).toBe('Midwife');
  });

  it('drops a cached label when the bundle changes under it', () => {
    // A bundle merged after first paint fires onTranslationChange, not
    // onLangChange, and the memo key does not change. Under the old per-pipe
    // memo the Title-Cased English cached before the bundle arrived died with
    // that pipe instance; the singleton would serve it to the whole app.
    expect(service.transform('MIDWIFE', 'role')).toBe('Midwife');

    translate.setTranslation(
      'en',
      { PORTAL: { ENUM: { ROLE: { MIDWIFE: 'Nurse-Midwife' } } } },
      true,
    );

    expect(service.transform('MIDWIFE', 'role')).toBe('Nurse-Midwife');
  });

  it('falls through to Title Case, which is the reason the enum gate exists', () => {
    // No key, no LABELS entry: a French page reads English and nothing warns.
    expect(service.transform('BLOOD_BANK_OFFICER', 'role')).toBe('Blood Bank Officer');
  });

  it("returns '' for a blank value so a template fallback can take over", () => {
    expect(service.transform(null, 'role')).toBe('');
    expect(service.transform(undefined, 'role')).toBe('');
    expect(service.transform('', 'role')).toBe('');
  });
});
