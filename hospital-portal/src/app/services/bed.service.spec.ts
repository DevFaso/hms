import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { BedService } from './bed.service';

describe('BedService', () => {
  let service: BedService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), BedService],
    });
    service = TestBed.inject(BedService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('targets each global write explicitly and does not leak that target to later reads', () => {
    const ward = { name: 'Cdx Ward', code: 'CDX', wardType: 'GENERAL' as const };
    const operations: [() => void, string, string, string][] = [
      [() => service.createWard(ward, 'h2').subscribe(), '/wards', 'POST', 'h2'],
      [() => service.updateWard('w2', ward, 'h2').subscribe(), '/wards/w2', 'PUT', 'h2'],
      [() => service.deleteWard('w2', 'h2').subscribe(), '/wards/w2', 'DELETE', 'h2'],
      [
        () => service.createBed('w1', { bedNumber: 'CDX' }, 'h1').subscribe(),
        '/wards/w1/beds',
        'POST',
        'h1',
      ],
      [
        () => service.updateBed('b1', { bedNumber: 'CDX' }, 'h1').subscribe(),
        '/beds/b1',
        'PUT',
        'h1',
      ],
      [
        () => service.updateBedStatus('b1', 'MAINTENANCE', 'Cdx reason', 'h1').subscribe(),
        '/beds/b1/status',
        'PATCH',
        'h1',
      ],
      [() => service.deleteBed('b1', 'h1').subscribe(), '/beds/b1', 'DELETE', 'h1'],
    ];
    for (const [run, url, method, hospital] of operations) {
      run();
      const req = httpMock.expectOne(url);
      expect(req.request.method).toBe(method);
      expect(req.request.headers.get('X-Hospital-Id')).toBe(hospital);
      req.flush({});
    }
    service.getWards().subscribe();
    const read = httpMock.expectOne('/wards');
    expect(read.request.headers.has('X-Hospital-Id')).toBeFalse();
    read.flush([]);
  });

  it('GETs wards without the inactive flag by default', () => {
    service.getWards().subscribe();
    const req = httpMock.expectOne('/wards');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GETs wards with includeInactive when requested', () => {
    service.getWards(true).subscribe();
    const req = httpMock.expectOne('/wards?includeInactive=true');
    req.flush([]);
  });

  it('POSTs a new ward', () => {
    service.createWard({ name: 'Maternity', code: 'MAT01', wardType: 'MATERNITY' }).subscribe();
    const req = httpMock.expectOne('/wards');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.code).toBe('MAT01');
    req.flush({ id: 'w1' });
  });

  it('PUTs ward updates', () => {
    service.updateWard('w1', { name: 'X', code: 'X1', wardType: 'GENERAL' }).subscribe();
    const req = httpMock.expectOne('/wards/w1');
    expect(req.request.method).toBe('PUT');
    req.flush({ id: 'w1' });
  });

  it('GETs the beds of a ward', () => {
    service.getBeds('w1').subscribe();
    const req = httpMock.expectOne('/wards/w1/beds');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GETs the available-bed picker feed', () => {
    service.getAvailableBeds().subscribe();
    const req = httpMock.expectOne('/beds/available');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('asks for the beds a given patient may take (QA D609)', () => {
    service.getAvailableBeds('p1').subscribe();
    const req = httpMock.expectOne((r) => r.url === '/beds/available');
    expect(req.request.params.get('patientId')).toBe('p1');
    req.flush([]);
  });

  it('POSTs a new bed under its ward', () => {
    service.createBed('w1', { bedNumber: 'B01' }).subscribe();
    const req = httpMock.expectOne('/wards/w1/beds');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.bedNumber).toBe('B01');
    req.flush({ id: 'b1' });
  });

  it('PATCHes a bed status change', () => {
    service.updateBedStatus('b1', 'MAINTENANCE', 'Broken rail').subscribe();
    const req = httpMock.expectOne('/beds/b1/status');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status: 'MAINTENANCE', notes: 'Broken rail' });
    req.flush({ id: 'b1', status: 'MAINTENANCE' });
  });

  it('DELETEs a bed', () => {
    service.deleteBed('b1').subscribe();
    const req = httpMock.expectOne('/beds/b1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
