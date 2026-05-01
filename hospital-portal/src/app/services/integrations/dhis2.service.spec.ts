import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { Dhis2Service } from './dhis2.service';
import {
  Dhis2DataElementMapping,
  Dhis2ExportRun,
  Dhis2FacilityConfig,
  Dhis2FacilityConfigRequest,
  Dhis2Page,
} from './dhis2.model';

describe('Dhis2Service', () => {
  let service: Dhis2Service;
  let httpMock: HttpTestingController;

  const baseConfig: Dhis2FacilityConfig = {
    id: 'cfg-1',
    hospitalId: 'h1',
    baseUrl: 'https://dhis2.example.org',
    authMode: 'PAT',
    authSecretEnvVar: 'DHIS2_TOKEN',
    authSecretConfigured: true,
    defaultPeriodType: 'MONTHLY',
    defaultDatasetUid: 'DS00000DEFK',
    lastExportAt: null,
    active: true,
    createdAt: '2026-05-01T10:00:00',
    updatedAt: '2026-05-01T10:00:00',
  };

  const baseRun: Dhis2ExportRun = {
    id: 'run-1',
    hospitalId: 'h1',
    datasetUid: 'DS00000DEFK',
    periodIso: '202604',
    triggeredByStaffId: null,
    startedAt: '2026-05-01T10:00:00',
    completedAt: '2026-05-01T10:00:01',
    status: 'SUCCESS',
    valueCount: 12,
    skippedCount: 0,
    httpStatus: 200,
    errorMessage: null,
    requestId: 'req-1',
  };

  const baseMapping: Dhis2DataElementMapping = {
    id: 'm1',
    hospitalId: 'h1',
    hmsConceptSystem: 'http://hl7.org/fhir/sid/cvx',
    hmsConceptCode: '49',
    dhis2DataElementUid: 'DE000000049',
    dhis2CategoryOptionComboUid: null,
    periodType: 'MONTHLY',
    datasetUid: 'DS00000DEFK',
    active: true,
    createdAt: '2026-05-01T10:00:00',
    updatedAt: '2026-05-01T10:00:00',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [Dhis2Service, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Dhis2Service);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the facility config with hospitalId', (done) => {
    service.getFacilityConfig('h1').subscribe((cfg) => {
      expect(cfg.id).toBe('cfg-1');
      done();
    });
    const req = httpMock.expectOne(
      (r) => r.url === '/admin/integrations/dhis2/facility' && r.params.get('hospitalId') === 'h1',
    );
    expect(req.request.method).toBe('GET');
    req.flush(baseConfig);
  });

  it('PUTs facility config (upsert)', (done) => {
    const body: Dhis2FacilityConfigRequest = {
      baseUrl: 'https://dhis2.example.org',
      authMode: 'PAT',
      authSecretEnvVar: 'DHIS2_TOKEN',
      defaultPeriodType: 'MONTHLY',
    };
    service.upsertFacilityConfig('h1', body).subscribe((cfg) => {
      expect(cfg.active).toBe(true);
      done();
    });
    const req = httpMock.expectOne(
      (r) => r.url === '/admin/integrations/dhis2/facility' && r.params.get('hospitalId') === 'h1',
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(baseConfig);
  });

  it('lists mappings paginated', (done) => {
    const page: Dhis2Page<Dhis2DataElementMapping> = {
      content: [baseMapping],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    service.listMappings('h1', 'DS00000DEFK', 0, 20).subscribe((p) => {
      expect(p.content.length).toBe(1);
      done();
    });
    const req = httpMock.expectOne(
      (r) =>
        r.url === '/admin/integrations/dhis2/mappings' &&
        r.params.get('hospitalId') === 'h1' &&
        r.params.get('datasetUid') === 'DS00000DEFK',
    );
    expect(req.request.method).toBe('GET');
    req.flush(page);
  });

  it('triggers an export with the supplied body', (done) => {
    service
      .triggerExport({
        hospitalId: 'h1',
        datasetUid: 'DS00000DEFK',
        periodType: 'MONTHLY',
        periodIso: '202604',
      })
      .subscribe((run) => {
        expect(run.status).toBe('SUCCESS');
        done();
      });
    const req = httpMock.expectOne('/admin/integrations/dhis2/exports/trigger');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.periodIso).toBe('202604');
    req.flush(baseRun);
  });

  it('lists runs paginated, latest first', (done) => {
    const page: Dhis2Page<Dhis2ExportRun> = {
      content: [baseRun],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    service.listRuns('h1').subscribe((p) => {
      expect(p.content[0].id).toBe('run-1');
      done();
    });
    const req = httpMock.expectOne(
      (r) =>
        r.url === '/admin/integrations/dhis2/exports/runs' && r.params.get('hospitalId') === 'h1',
    );
    expect(req.request.method).toBe('GET');
    req.flush(page);
  });

  it('DELETEs a mapping by id', (done) => {
    service.deleteMapping('m1', 'h1').subscribe(() => done());
    const req = httpMock.expectOne(
      (r) =>
        r.url === '/admin/integrations/dhis2/mappings/m1' && r.params.get('hospitalId') === 'h1',
    );
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
