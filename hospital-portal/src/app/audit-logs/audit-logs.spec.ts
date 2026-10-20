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
      AUDIT: { WRITE_POST: 'Création : {{entity}}', WRITE_ENTITY_UNKNOWN: 'enregistrement' },
      PORTAL: {
        ENUM: {
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

  it('does not glue the stat-card number to its label', () => {
    const fixture = TestBed.createComponent(AuditLogsComponent);
    fixture.detectChanges();
    const card = (fixture.nativeElement as HTMLElement).querySelector('.card-info');
    expect(card?.textContent?.replaceAll(/\s+/g, ' ').trim()).toMatch(/^12 \S/);
  });
});
