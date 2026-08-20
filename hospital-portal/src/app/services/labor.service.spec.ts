import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { LaborService } from './labor.service';

describe('LaborService', () => {
  let service: LaborService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), LaborService],
    });
    service = TestBed.inject(LaborService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new labor episode', () => {
    service.startEpisode('p1', { membraneStatus: 'INTACT' }).subscribe();
    const req = httpMock.expectOne('/patients/p1/labor/episodes');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.membraneStatus).toBe('INTACT');
    req.flush({ id: 'e1' });
  });

  it('GETs the episode list with a limit', () => {
    service.episodes('p1', 5).subscribe();
    const req = httpMock.expectOne('/patients/p1/labor/episodes?limit=5');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POSTs a partograph entry under its episode', () => {
    service.addEntry('p1', 'e1', { cervicalDilationCm: 6, fetalHeartRateBpm: 140 }).subscribe();
    const req = httpMock.expectOne('/patients/p1/labor/episodes/e1/entries');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.cervicalDilationCm).toBe(6);
    req.flush({ id: 'pe1', alerts: [] });
  });

  it('GETs an episode entry list', () => {
    service.entries('p1', 'e1').subscribe();
    const req = httpMock.expectOne('/patients/p1/labor/episodes/e1/entries');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POSTs the delivery record', () => {
    service
      .recordDelivery('p1', 'e1', {
        deliveryMode: 'SPONTANEOUS_VAGINAL',
        estimatedBloodLossMl: 300,
      })
      .subscribe();
    const req = httpMock.expectOne('/patients/p1/labor/episodes/e1/delivery');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.deliveryMode).toBe('SPONTANEOUS_VAGINAL');
    req.flush({ id: 'd1', alerts: [] });
  });

  it('GETs the delivery record', () => {
    service.delivery('p1', 'e1').subscribe();
    const req = httpMock.expectOne('/patients/p1/labor/episodes/e1/delivery');
    expect(req.request.method).toBe('GET');
    req.flush({ id: 'd1' });
  });
});
