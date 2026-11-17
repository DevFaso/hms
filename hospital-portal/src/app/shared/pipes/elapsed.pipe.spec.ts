import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ElapsedPipe } from './elapsed.pipe';

describe('ElapsedPipe (QA D1479)', () => {
  let pipe: ElapsedPipe;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      ELAPSED: {
        MINUTES: '{{minutes}} min',
        HOURS: '{{hours}} h {{minutes}} min',
        DAYS: '{{days}} j {{hours}} h',
      },
    });
    translate.use('fr');
    pipe = TestBed.runInInjectionContext(() => new ElapsedPipe());
  });

  it('reads a long wait as time, never as raw minutes', () => {
    expect(pipe.transform(7151)).toBe('4 j 23 h');
    expect(pipe.transform(1440)).toBe('1 j 0 h');
    expect(pipe.transform(135)).toBe('2 h 15 min');
    expect(pipe.transform(60)).toBe('1 h 0 min');
    expect(pipe.transform(59)).toBe('59 min');
    expect(pipe.transform(0)).toBe('0 min');
  });

  it('shows nothing for a missing value and never a negative wait', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform(-5)).toBe('0 min');
  });
});
