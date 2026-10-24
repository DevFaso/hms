import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';
import { AuditLogService, AuditEventLog } from '../services/audit-log.service';
import { ToastService } from '../core/toast.service';
import { AuditLogsComponent } from './audit-logs';

describe('AuditLogsComponent (QA D330)', () => {
  let component: AuditLogsComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AuditLogsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: AuditLogService,
          useValue: {
            list: () => of({ content: [], totalElements: 12, totalPages: 1 }),
            getEventTypeStatus: () => of([]),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      AUDIT: {
        WRITE_POST: 'Création : {{entity}}',
        WRITE_ENTITY_UNKNOWN: 'enregistrement',
        DESC: {
          PATIENT_RECORD_VIEWED: 'Dossier patient consulté',
          ROLE_ASSIGNED: 'Rôle {{role}} attribué à {{name}}',
          LAB_DEFINITION_STEP: 'Examen {{code}} : étape {{step}}, statut {{status}}.',
        },
      },
      PORTAL: {
        ENUM: {
          ROLE: { PATIENT: 'Patient(e)', NURSE: 'Infirmier(ère)' },
          LAB_DEFINITION_ACTION: { SUBMIT_FOR_QA: 'Soumission à la revue qualité' },
          AUDIT_ENTITY_TYPE: {
            LAB_TEST_DEFINITION: 'Définition d’examen de laboratoire',
            REFERRAL: 'Orientation',
          },
        },
      },
    });
    translate.use('fr');
    component = TestBed.createComponent(AuditLogsComponent).componentInstance;
  });

  const row = (over: Partial<AuditEventLog>) => ({ ...over }) as AuditEventLog;

  it('reads a class-name or enum entity type in the reader language', () => {
    expect(component.entityLabel('LabTestDefinition')).toBe('Définition d’examen de laboratoire');
    expect(component.entityLabel('REFERRAL')).toBe('Orientation');
    expect(component.entityLabel(null)).toBe('--');
  });

  it('describes a generic write row as an action, never as a raw route', () => {
    const text = component.describe(
      row({ eventDescription: 'POST /api/referrals/{id}/accept', entityType: 'REFERRAL' }),
    );
    expect(text).toBe('Création : Orientation');
    expect(text).not.toContain('/api');
  });

  it('keeps a written description as it is', () => {
    expect(component.describe(row({ eventDescription: 'Lab test definition approval step' }))).toBe(
      'Lab test definition approval step',
    );
  });

  it('D774: reads a known English description in the reader language', () => {
    expect(component.describe(row({ eventDescription: 'Patient record viewed' }))).toBe(
      'Dossier patient consulté',
    );
    expect(
      component.describe(row({ eventDescription: "Assigned role 'ROLE_PATIENT' to Awa Traoré" })),
    ).toBe('Rôle Patient(e) attribué à Awa Traoré');
  });

  it('D774: never shows a raw role or action code in a description', () => {
    const text = component.describe(
      row({
        eventDescription:
          'Examen de laboratoire GLU : étape d’approbation SUBMIT_FOR_QA, nouveau statut En revue (ROLE_NURSE).',
      }),
    );
    expect(text).toContain('Soumission à la revue qualité');
    expect(text).toContain('Infirmier(ère)');
    expect(text).not.toMatch(/SUBMIT_FOR_QA|ROLE_NURSE/);
  });

  it('D774: keeps identifiers verbatim (test codes, person names, underscore codes)', () => {
    expect(
      component.describe(
        row({ eventDescription: 'Lab test GLU and HB_A1C updated by ROLE_NURSE' }),
      ),
    ).toBe('Lab test GLU and HB_A1C updated by Infirmier(ère)');
    expect(
      component.describe(row({ eventDescription: "Assigned role 'ROLE_PATIENT' to AWA TRAORE" })),
    ).toBe('Rôle Patient(e) attribué à AWA TRAORE');
    expect(
      component.describe(
        row({
          eventDescription:
            'Lab test definition GLU: approval step SUBMIT_FOR_QA, new status PENDING_QA_REVIEW.',
        }),
      ),
    ).toBe('Examen GLU : étape Soumission à la revue qualité, statut PENDING_QA_REVIEW.');
  });

  it('does not glue the stat-card number to its label', () => {
    const fixture = TestBed.createComponent(AuditLogsComponent);
    fixture.detectChanges();
    const card = (fixture.nativeElement as HTMLElement).querySelector('.card-info');
    expect(card?.textContent?.replaceAll(/\s+/g, ' ').trim()).toMatch(/^12 \S/);
  });
});
