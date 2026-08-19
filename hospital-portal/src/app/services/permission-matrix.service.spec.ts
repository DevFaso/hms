import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { PermissionMatrixService } from './permission-matrix.service';

describe('PermissionMatrixService', () => {
  let service: PermissionMatrixService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PermissionMatrixService],
    });
    service = TestBed.inject(PermissionMatrixService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a snapshot without a client-set version', () => {
    service
      .createSnapshot({
        environment: 'STAGING',
        label: 'v2 draft',
        rows: [{ domain: 'Lab', actions: ['read'], owners: ['LAB_MANAGER'] }],
      })
      .subscribe((snap) => expect(snap.version).toBe(3));
    const req = httpMock.expectOne('/permissions/matrix/snapshots');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.version).toBeUndefined();
    req.flush({
      id: 's1',
      environment: 'STAGING',
      version: 3,
      createdAt: '2026-01-01T00:00:00Z',
      rows: [],
    });
  });

  it('GETs latest with required environment param', () => {
    service.latestSnapshot('PRODUCTION').subscribe();
    const req = httpMock.expectOne('/permissions/matrix/snapshots/latest?environment=PRODUCTION');
    expect(req.request.method).toBe('GET');
    req.flush({
      id: 's1',
      environment: 'PRODUCTION',
      version: 1,
      createdAt: '2026-01-01T00:00:00Z',
      rows: [],
    });
  });

  it('GETs the snapshot list as a bare array, optionally filtered', () => {
    service.listSnapshots('BASELINE').subscribe((list) => expect(list.length).toBe(1));
    const req = httpMock.expectOne('/permissions/matrix/snapshots?environment=BASELINE');
    req.flush([
      {
        id: 's1',
        environment: 'BASELINE',
        version: 1,
        createdAt: '2026-01-01T00:00:00Z',
        rows: [],
      },
    ]);
  });

  it('GETs audit with an action filter', () => {
    service.listAudit('COMPARISON_RUN').subscribe((list) => expect(list.length).toBe(0));
    const req = httpMock.expectOne('/permissions/matrix/audit?action=COMPARISON_RUN');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
