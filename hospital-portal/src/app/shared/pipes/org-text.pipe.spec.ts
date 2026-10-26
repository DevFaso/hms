import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { OrgTextPipe, seededOrgTextKey } from './org-text.pipe';
import en from '../../../assets/i18n/en.json';
import fr from '../../../assets/i18n/fr.json';
import es from '../../../assets/i18n/es.json';

/**
 * QA D822: /organizations in French or Spanish showed the default
 * organisation's English seed text ("Default Healthcare Organization").
 */
describe('OrgTextPipe (QA D822)', () => {
  const SEED_NAME = 'Default Healthcare Organization';
  const SEED_DESC = 'Default organization for hospitals without specific organization assignment';
  let pipe: OrgTextPipe;
  let translate: TranslateService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.setTranslation('es', es);
    translate.setTranslation('en', en);
    translate.use('fr');
    pipe = TestBed.runInInjectionContext(() => new OrgTextPipe());
  });

  afterEach(() => pipe.ngOnDestroy());

  it('shows the seed name and description in French and Spanish', () => {
    expect(pipe.transform(SEED_NAME, 'DEFAULT_ORG')).toBe(fr.ORGANIZATIONS.DEFAULT_ORG_NAME);
    expect(pipe.transform(SEED_DESC, 'DEFAULT_ORG', 'description')).toBe(
      fr.ORGANIZATIONS.DEFAULT_ORG_DESCRIPTION,
    );
    translate.use('es');
    expect(pipe.transform(SEED_NAME, 'DEFAULT_ORG')).toBe(es.ORGANIZATIONS.DEFAULT_ORG_NAME);
    expect(pipe.transform(SEED_NAME, 'DEFAULT_ORG')).not.toBe(SEED_NAME);
  });

  it('keeps a name an admin gave the default organisation', () => {
    expect(pipe.transform('Réseau national', 'DEFAULT_ORG')).toBe('Réseau national');
  });

  it('never touches another organisation, even one named like the seed', () => {
    expect(pipe.transform(SEED_NAME, 'RSFASO')).toBe(SEED_NAME);
    expect(seededOrgTextKey('Réseau Santé Faso', 'RSFASO')).toBeNull();
  });

  it('translates by the seed name alone where the row carries no code', () => {
    expect(pipe.transform(SEED_NAME)).toBe(fr.ORGANIZATIONS.DEFAULT_ORG_NAME);
  });

  it('passes empty values through', () => {
    expect(pipe.transform(null, 'DEFAULT_ORG')).toBe('');
    expect(pipe.transform(undefined)).toBe('');
  });

  it('carries real French and Spanish, not the English seed', () => {
    for (const bundle of [fr, es]) {
      expect(bundle.ORGANIZATIONS.DEFAULT_ORG_NAME).not.toBe(SEED_NAME);
      expect(bundle.ORGANIZATIONS.DEFAULT_ORG_DESCRIPTION).not.toBe(SEED_DESC);
    }
  });
});
