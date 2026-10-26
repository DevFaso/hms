import { TestBed, fakeAsync, tick } from '@angular/core/testing';
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
          HOSPITAL_SCOPE_REFUSED: 'Accès à l’hôpital refusé ({{reason}})',
        },
        SCOPE_REFUSAL: { NOT_PERMITTED: 'aucun rôle actif dans cet hôpital' },
      },
      LAB: { APPROVAL_STATUS: { PENDING_QA_REVIEW: 'En attente de revue qualité' } },
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

  it('QA D869: a hospital-scope refusal reads in the reader language, never as a raw enum', () => {
    const text = component.describe(
      row({ eventDescription: 'Hospital scope refused (NOT_PERMITTED)' }),
    );
    expect(text).toBe('Accès à l’hôpital refusé (aucun rôle actif dans cet hôpital)');
    expect(text).not.toContain('NOT_PERMITTED');
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
    ).toBe('Examen GLU : étape Soumission à la revue qualité, statut En attente de revue qualité.');
  });

  it('does not glue the stat-card number to its label', () => {
    const fixture = TestBed.createComponent(AuditLogsComponent);
    fixture.detectChanges();
    const card = (fixture.nativeElement as HTMLElement).querySelector('.card-info');
    expect(card?.textContent?.replaceAll(/\s+/g, ' ').trim()).toMatch(/^12 \S/);
  });
});

describe('AuditLogsComponent (QA D794: the server searches every page)', () => {
  let component: AuditLogsComponent;
  let list: jasmine.Spy;

  const page = (content: Partial<AuditEventLog>[], totalPages = 1) =>
    of({ content: content as AuditEventLog[], totalElements: content.length, totalPages });

  beforeEach(() => {
    list = jasmine.createSpy('list').and.returnValue(page([]));
    TestBed.configureTestingModule({
      imports: [AuditLogsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AuditLogService, useValue: { list, getEventTypeStatus: () => of([]) } },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
      ],
    });
    component = TestBed.createComponent(AuditLogsComponent).componentInstance;
    component.ngOnInit();
    list.calls.reset();
  });

  it('sends the typed search to the server, from the first page, after the user pauses', fakeAsync(() => {
    component.currentPage.set(3);
    component.searchTerm = ' Multi91064 ';
    component.onSearchInput();
    tick(100);
    expect(list).not.toHaveBeenCalled();
    tick(300);

    expect(list).toHaveBeenCalledTimes(1);
    expect(list.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ page: 0, search: 'Multi91064' }),
    );
  }));

  it('shows what the server returned for the search, never a client-side filter of the old page', fakeAsync(() => {
    list.and.returnValue(page([{ userName: 'Awa', eventDescription: 'New user account created' }]));
    component.searchTerm = 'Multi91064';
    component.onSearchInput();
    tick(300);

    expect(component.logs().length).toBe(1);
  }));

  it('sends the event type to the server and goes back to the first page', () => {
    component.currentPage.set(2);
    component.selectedEventType = 'INVOICE_UPDATED';
    component.onEventTypeChange();

    expect(list).toHaveBeenCalledTimes(1);
    expect(list.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ page: 0, eventType: 'INVOICE_UPDATED' }),
    );
  });

  it('keeps the search and the event type when paging', () => {
    list.and.returnValue(page([{ userName: 'Awa' }], 3));
    component.searchTerm = 'awa';
    component.selectedEventType = 'LOGIN';
    component.load();
    list.calls.reset();

    component.nextPage();

    expect(list.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ page: 1, search: 'awa', eventType: 'LOGIN' }),
    );
  });

  it('sends no search or event type when both are empty', () => {
    component.load();

    const params = list.calls.mostRecent().args[0] as Record<string, unknown>;
    expect(params['search']).toBeUndefined();
    expect(params['eventType']).toBeUndefined();
  });
});
