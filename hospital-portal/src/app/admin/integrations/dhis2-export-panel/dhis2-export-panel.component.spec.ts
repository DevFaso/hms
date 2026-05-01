import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

import { Dhis2ExportPanelComponent } from './dhis2-export-panel.component';
import { Dhis2Service } from '../../../services/integrations/dhis2.service';
import { RoleContextService } from '../../../core/role-context.service';
import { ToastService } from '../../../core/toast.service';

describe('Dhis2ExportPanelComponent', () => {
  let fixture: ComponentFixture<Dhis2ExportPanelComponent>;
  let component: Dhis2ExportPanelComponent;
  let httpMock: HttpTestingController;
  let dhis2: jasmine.SpyObj<Dhis2Service>;
  let roleContext: jasmine.SpyObj<RoleContextService>;
  let toast: jasmine.SpyObj<ToastService>;

  const sampleRun = {
    id: 'run-1',
    hospitalId: 'h1',
    datasetUid: 'DS00000DEFK',
    periodIso: '202604',
    triggeredByStaffId: null,
    startedAt: '2026-05-01T10:00:00',
    completedAt: '2026-05-01T10:00:01',
    status: 'SUCCESS' as const,
    valueCount: 12,
    skippedCount: 0,
    httpStatus: 200,
    errorMessage: null,
    requestId: 'req-1',
  };

  beforeEach(() => {
    dhis2 = jasmine.createSpyObj('Dhis2Service', ['listRuns', 'triggerExport']);
    roleContext = jasmine.createSpyObj('RoleContextService', [], {
      activeHospitalId: 'h1',
      activeHospitalIdSignal: () => 'h1',
    });
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    dhis2.listRuns.and.returnValue(
      of({
        content: [sampleRun],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      }),
    );

    TestBed.configureTestingModule({
      imports: [Dhis2ExportPanelComponent, TranslateModule.forRoot()],
      providers: [
        { provide: Dhis2Service, useValue: dhis2 },
        { provide: RoleContextService, useValue: roleContext },
        { provide: ToastService, useValue: toast },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    fixture = TestBed.createComponent(Dhis2ExportPanelComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads recent runs on init when a hospital is active', () => {
    fixture.detectChanges();
    expect(dhis2.listRuns).toHaveBeenCalledWith('h1');
    expect((component as any).runs().length).toBe(1);
  });

  it('shows error state when the runs request fails', () => {
    dhis2.listRuns.and.returnValue(throwError(() => new Error('network')));
    fixture.detectChanges();
    expect((component as any).error()).toBe(true);
    expect((component as any).runs().length).toBe(0);
  });

  it('canTrigger() is false until both UID and period are valid', () => {
    fixture.detectChanges();
    expect((component as any).canTrigger()).toBe(false);
    (component as any).datasetUid = 'DS00000DEFK';
    (component as any).periodIso = '202604';
    expect((component as any).canTrigger()).toBe(true);
  });

  it('onTrigger() calls service with the form payload', () => {
    dhis2.triggerExport.and.returnValue(of(sampleRun));
    fixture.detectChanges();
    (component as any).datasetUid = 'DS00000DEFK';
    (component as any).periodIso = '202604';
    (component as any).onTrigger();

    expect(dhis2.triggerExport).toHaveBeenCalledWith({
      hospitalId: 'h1',
      datasetUid: 'DS00000DEFK',
      periodType: 'MONTHLY',
      periodIso: '202604',
    });
    expect(toast.success).toHaveBeenCalled();
  });

  it('cancels in-flight runs request on rapid double-trigger (no late-stale paint)', () => {
    const slow = new Subject<any>();
    dhis2.triggerExport.and.returnValue(slow);
    fixture.detectChanges();
    (component as any).datasetUid = 'DS00000DEFK';
    (component as any).periodIso = '202604';
    (component as any).onTrigger();
    expect((component as any).triggering()).toBe(true);

    // While the first trigger is in flight, onTrigger short-circuits.
    (component as any).onTrigger();
    expect(dhis2.triggerExport).toHaveBeenCalledTimes(1);
  });

  it('statusClass returns the right pill modifier per status', () => {
    expect((component as any).statusClass('SUCCESS')).toBe('status-pill--success');
    expect((component as any).statusClass('PARTIAL')).toBe('status-pill--partial');
    expect((component as any).statusClass('FAILED')).toBe('status-pill--failed');
    expect((component as any).statusClass('PENDING')).toBe('status-pill--pending');
  });
});
