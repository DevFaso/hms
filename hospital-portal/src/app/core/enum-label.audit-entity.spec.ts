import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';
import { EnumLabelService } from './enum-label.service';

/**
 * QA D619 (regression of D528): the admin dashboard's "Activité récente" card
 * printed each audit row's resourceName — TRANSFER, PRESCRIPTION,
 * BILLING_INVOICE, INVOICE_ITEM, LAB_TEST_DEFINITION, SIGNATURE, APPOINTMENT —
 * as the raw token in FR and ES. D528 keyed every one of them, but the
 * dashboard (and the super-admin feed and audit search) echoed the field
 * instead of reading the key. These are the exact tokens from the report,
 * against the real locale files.
 */
describe('EnumLabelService.auditEntityLabel (QA D619)', () => {
  const QA_TOKENS = [
    'TRANSFER',
    'PRESCRIPTION',
    'BILLING_INVOICE',
    'INVOICE_ITEM',
    'LAB_TEST_DEFINITION',
    'SIGNATURE',
    'APPOINTMENT',
  ];
  let service: EnumLabelService;
  let translate: TranslateService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', en);
    translate.setTranslation('fr', fr);
    translate.setTranslation('es', es);
    service = TestBed.inject(EnumLabelService);
  });

  for (const lang of ['fr', 'es']) {
    it(`never shows the wire token in ${lang}`, () => {
      translate.use(lang);
      for (const token of QA_TOKENS) {
        const label = service.auditEntityLabel(token);
        expect(label).withContext(`${lang} ${token}`).not.toBe(token);
        expect(label).withContext(`${lang} ${token}`).toBeTruthy();
      }
    });
  }

  it('reads a class-name token the same way as a snake token', () => {
    translate.use('fr');
    expect(service.auditEntityLabel('LabTestDefinition')).toBe(
      service.auditEntityLabel('LAB_TEST_DEFINITION'),
    );
  });

  it('keeps a name someone typed, and hides blank or unknown resources', () => {
    translate.use('fr');
    expect(service.auditEntityLabel('Pédiatrie 2')).toBe('Pédiatrie 2');
    expect(service.auditEntityLabel('  ')).toBeNull();
    expect(service.auditEntityLabel('Unknown Resource')).toBeNull();
    expect(service.auditEntityLabel(null)).toBeNull();
  });
});
