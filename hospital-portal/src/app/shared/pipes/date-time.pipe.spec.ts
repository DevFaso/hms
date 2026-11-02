import { registerLocaleData } from '@angular/common';
import localeEs from '@angular/common/locales/es';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DateTimePipe } from './date-time.pipe';

describe('DateTimePipe (QA D1340: no seconds in a displayed date-time)', () => {
  beforeAll(() => {
    registerLocaleData(localeFr);
    registerLocaleData(localeEs);
  });

  function pipeFor(locale: string): DateTimePipe {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: locale }] });
    return TestBed.runInInjectionContext(() => new DateTimePipe());
  }

  const at = new Date(2026, 10, 2, 15, 0, 37);

  it('French: "2 nov. 2026, 15:00", the seconds dropped', () => {
    expect(pipeFor('fr').transform(at)).toBe('2 nov. 2026, 15:00');
  });

  it('Spanish: "2 nov 2026, 15:00"', () => {
    expect(pipeFor('es').transform(at)).toBe('2 nov 2026, 15:00');
  });

  it('English: the medium date and short time, in English order', () => {
    // CLDR puts a narrow no-break space before "PM".
    expect(pipeFor('en-US').transform(at).replace(/\s/g, ' ')).toBe('Nov 2, 2026, 3:00 PM');
  });

  it('reads a string as the date pipe does, and gives "" for nothing or garbage', () => {
    const pipe = pipeFor('fr');
    expect(pipe.transform('2026-11-02T15:00:37')).toBe('2 nov. 2026, 15:00');
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform('')).toBe('');
    expect(pipe.transform('not a date')).toBe('');
  });
});
