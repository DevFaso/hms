import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { BedService } from './bed.service';

describe('BedService', () => {
  let service: BedService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), BedService],
    });
    service = TestBed.inject(BedService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

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
