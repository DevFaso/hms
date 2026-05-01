import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject } from 'rxjs';

import { Dhis2MappingEditorComponent } from './dhis2-mapping-editor.component';
import { Dhis2Service } from '../../../services/integrations/dhis2.service';
import { RoleContextService } from '../../../core/role-context.service';
import { ToastService } from '../../../core/toast.service';

describe('Dhis2MappingEditorComponent', () => {
  let fixture: ComponentFixture<Dhis2MappingEditorComponent>;
  let component: Dhis2MappingEditorComponent;
  let httpMock: HttpTestingController;
  let dhis2: jasmine.SpyObj<Dhis2Service>;
  let roleContext: jasmine.SpyObj<RoleContextService>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(() => {
    dhis2 = jasmine.createSpyObj('Dhis2Service', [
      'listMappings',
      'createMapping',
      'deleteMapping',
    ]);
    roleContext = jasmine.createSpyObj('RoleContextService', [], {
      activeHospitalId: 'h1',
      activeHospitalIdSignal: () => 'h1',
    });
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);

    TestBed.configureTestingModule({
      imports: [Dhis2MappingEditorComponent, TranslateModule.forRoot()],
      providers: [
        { provide: Dhis2Service, useValue: dhis2 },
        { provide: RoleContextService, useValue: roleContext },
        { provide: ToastService, useValue: toast },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    fixture = TestBed.createComponent(Dhis2MappingEditorComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('does not auto-load on init (waits for filter input)', () => {
    fixture.detectChanges();
    expect(dhis2.listMappings).not.toHaveBeenCalled();
  });

  it('refreshes list when filterDatasetUid becomes a valid 11-char UID', () => {
    const result = new Subject<{
      content: any[];
      totalElements: number;
      totalPages: number;
      number: number;
      size: number;
    }>();
    dhis2.listMappings.and.returnValue(result);

    fixture.detectChanges();
    (component as any).filterDatasetUid = 'DS00000DEFK';
    (component as any).onFilterChange();

    expect(dhis2.listMappings).toHaveBeenCalledWith('h1', 'DS00000DEFK');
  });

  it('REGRESSION: onFilterChange syncs newRow.datasetUid (Copilot bug #1)', () => {
    dhis2.listMappings.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
    );
    fixture.detectChanges();
    (component as any).filterDatasetUid = 'DS00000DEFK';
    (component as any).onFilterChange();

    // canAdd() also requires the other fields filled, so populate them first.
    (component as any).newRow.dhis2DataElementUid = 'DE000000049';
    (component as any).newRow.hmsConceptCode = '49';

    expect((component as any).newRow.datasetUid).toBe('DS00000DEFK');
    expect((component as any).canAdd()).toBe(true);
  });

  it('clears rows when filter is not a valid UID', () => {
    fixture.detectChanges();
    (component as any).filterDatasetUid = 'tooShort';
    (component as any).onFilterChange();

    expect((component as any).rows().length).toBe(0);
    expect(dhis2.listMappings).not.toHaveBeenCalled();
  });

  it('onAdd POSTs the mapping and refreshes on success', () => {
    const created = {
      id: 'm1',
      hospitalId: 'h1',
      hmsConceptSystem: 'http://hl7.org/fhir/sid/cvx',
      hmsConceptCode: '49',
      dhis2DataElementUid: 'DE000000049',
      dhis2CategoryOptionComboUid: null,
      periodType: 'MONTHLY' as const,
      datasetUid: 'DS00000DEFK',
      active: true,
      createdAt: '2026-05-01T10:00:00',
      updatedAt: '2026-05-01T10:00:00',
    };
    dhis2.createMapping.and.returnValue(of(created));
    dhis2.listMappings.and.returnValue(
      of({
        content: [created],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      }),
    );

    fixture.detectChanges();
    (component as any).filterDatasetUid = 'DS00000DEFK';
    (component as any).onFilterChange();
    (component as any).newRow.hmsConceptCode = '49';
    (component as any).newRow.dhis2DataElementUid = 'DE000000049';
    (component as any).onAdd();

    expect(dhis2.createMapping).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('Mapping added');
  });
});
