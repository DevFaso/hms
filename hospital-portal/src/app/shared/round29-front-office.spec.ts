import { TestBed } from '@angular/core/testing';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';

import en from '../../assets/i18n/en.json';
import es from '../../assets/i18n/es.json';
import fr from '../../assets/i18n/fr.json';
import { genderedKey } from './i18n/born-label';
import { orDash } from './pipes/dash.pipe';
import { keyLabel } from './pipes/key-label.pipe';

describe('round-29 front-office shared pieces', () => {
  it('QA D1328: a blank string reads "—" like null; 0 and text are kept', () => {
    expect(orDash('')).toBe('—');
    expect(orDash('   ')).toBe('—');
    expect(orDash(null)).toBe('—');
    expect(orDash(undefined)).toBe('—');
    expect(orDash(0)).toBe(0);
    expect(orDash('Kadiogo')).toBe('Kadiogo');
  });

  it('QA D1323: a server value outside the dictionary shows as sent, never as its key', () => {
    TestBed.configureTestingModule({
      providers: [provideTranslateService({ defaultLanguage: 'fr' })],
    });
    const t = TestBed.inject(TranslateService);
    t.setTranslation('fr', fr);
    t.use('fr');
    expect(keyLabel(t, 'URGENT', 'RECEPTION.')).toBe(t.instant('RECEPTION.URGENT'));
    expect(keyLabel(t, 'URGENTISSIME', 'RECEPTION.')).toBe('URGENTISSIME');
    expect(keyLabel(t, '', 'RECEPTION.')).toBe('—');
    expect(keyLabel(t, null, 'RECEPTION.')).toBe('—');
  });

  it('QA D1327: the duplicate card agrees with a woman in French and Spanish', () => {
    TestBed.configureTestingModule({
      providers: [provideTranslateService({ defaultLanguage: 'fr' })],
    });
    const t = TestBed.inject(TranslateService);
    t.setTranslation('fr', fr);
    t.setTranslation('es', es);
    t.setTranslation('en', en);
    const say = (lang: string, base: string, sex: string | null) => {
      t.use(lang);
      return t.instant(genderedKey(base, sex), { count: 2 });
    };
    expect(say('fr', 'PATIENTS.REGISTERED_IN_MANY', 'FEMALE')).toBe('enregistrée dans 2 hôpitaux');
    expect(say('es', 'PATIENTS.REGISTERED_IN_MANY', 'FEMALE')).toBe('registrada en 2 hospitales');
    expect(say('fr', 'PATIENTS.REGISTERED_IN_MANY', 'MALE')).toBe('enregistré dans 2 hôpitaux');
    expect(say('fr', 'PATIENTS.REGISTERED_IN_MANY', null)).toBe('enregistré(e) dans 2 hôpitaux');
    expect(say('es', 'PATIENTS.ALREADY_REGISTERED_HERE', 'F')).toBe(
      'Ya registrada en este hospital',
    );
    expect(say('en', 'PATIENTS.REGISTERED_IN_ONE', 'FEMALE')).toBe('registered at 2 hospital');
    expect(genderedKey('PATIENTS.BORN', 'OTHER')).toBe('PATIENTS.BORN');
  });
});
