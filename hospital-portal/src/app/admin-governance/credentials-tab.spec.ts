import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';
import { CredentialsTabComponent } from './credentials-tab';
import {
  CredentialHealth,
  SuperAdminGovernanceService,
} from '../services/super-admin-governance.service';
import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

describe('D2006: recovery types on the administrator detail', () => {
  for (const [language, bundle, expected] of [
    ['en', en, ['Email', 'Phone', 'Security key', 'Personal contact', 'Other contact']],
    ['fr', fr, ['E-mail', 'Téléphone', 'Clé de sécurité', 'Contact personnel', 'Autre contact']],
    [
      'es',
      es,
      [
        'Correo electrónico',
        'Teléfono',
        'Llave de seguridad',
        'Contacto personal',
        'Otro contacto',
      ],
    ],
  ] as const) {
    it(`labels every backend type in ${language}`, () => {
      TestBed.configureTestingModule({
        imports: [CredentialsTabComponent, TranslateModule.forRoot()],
        providers: [
          { provide: SuperAdminGovernanceService, useValue: { credentialHealth: () => of([]) } },
        ],
      });
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation(language, bundle);
      translate.use(language);
      const fixture = TestBed.createComponent(CredentialsTabComponent);
      fixture.detectChanges();
      const health: CredentialHealth = {
        userId: 'synthetic-user',
        username: 'synthetic.test',
        active: true,
        forcePasswordChange: false,
        forceUsernameChange: false,
        mfaEnrolledCount: 0,
        verifiedMfaCount: 0,
        hasPrimaryMfa: false,
        recoveryContactCount: 5,
        verifiedRecoveryContacts: 0,
        hasPrimaryRecoveryContact: false,
        mfaEnrollments: [],
        recoveryContacts: (
          ['EMAIL', 'PHONE', 'SECURITY_KEY', 'PERSONAL_CONTACT', 'OTHER'] as const
        ).map((contactType) => ({
          contactType,
          contactValue: 'synthetic@example.test',
          verified: false,
          primaryContact: false,
        })),
      };
      fixture.componentInstance.openDetail(health);
      fixture.detectChanges();
      const rows = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('[role="dialog"] tbody tr'),
      );
      expect(rows.map((row) => row.querySelector('td')?.textContent?.trim())).toEqual([
        ...expected,
      ]);
      fixture.destroy();
    });
  }
});
