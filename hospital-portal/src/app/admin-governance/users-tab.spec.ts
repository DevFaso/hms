import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';
import { UsersTabComponent } from './users-tab';
import { SuperAdminGovernanceService } from '../services/super-admin-governance.service';
import { HospitalService } from '../services/hospital.service';
import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

describe('password rotation age labels', () => {
  for (const [language, bundle, labels] of [
    ['en', en, ['0 days', '1 day', '2 days']],
    ['fr', fr, ['0 jours', '1 jour', '2 jours']],
    ['es', es, ['0 d\u00edas', '1 d\u00eda', '2 d\u00edas']],
  ] as const) {
    it(`renders singular and plural ages in ${language}`, () => {
      const rows = [0, 1, 2].map((age) => ({
        userId: `user-${age}`,
        passwordAgeDays: age,
        daysUntilDue: 10,
        forcePasswordChange: false,
        status: 'HEALTHY',
      }));
      TestBed.configureTestingModule({
        imports: [UsersTabComponent, TranslateModule.forRoot()],
        providers: [
          { provide: SuperAdminGovernanceService, useValue: { passwordRotation: () => of(rows) } },
          { provide: HospitalService, useValue: { list: () => of([]) } },
        ],
      });
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation(language, bundle);
      translate.use(language);
      const fixture = TestBed.createComponent(UsersTabComponent);
      fixture.detectChanges();
      const rendered = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr'),
      ).map((row) => row.querySelectorAll('td')[1]?.textContent?.trim());
      expect(rendered).toEqual([...labels]);
      fixture.destroy();
    });
  }
});
