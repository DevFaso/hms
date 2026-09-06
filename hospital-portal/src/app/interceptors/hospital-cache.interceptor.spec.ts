import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HospitalService } from '../services/hospital.service';
import { hospitalCacheInterceptor } from './hospital-cache.interceptor';

describe('hospitalCacheInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let hospitals: jasmine.SpyObj<HospitalService>;

  beforeEach(() => {
    hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', ['forgetFirstPage']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([hospitalCacheInterceptor])),
        provideHttpClientTesting(),
        { provide: HospitalService, useValue: hospitals },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('forgets the first page after a lifecycle write on a hospital, whichever service sent it', () => {
    http.post('/super-admin/hospitals/h1/suspend', {}).subscribe();
    httpMock.expectOne('/super-admin/hospitals/h1/suspend').flush({});
    expect(hospitals.forgetFirstPage).toHaveBeenCalledTimes(1);
  });

  it('forgets it after an organisation write too — archiving the chain archives its hospitals', () => {
    http.post('/super-admin/organizations/o1/archive', {}).subscribe();
    httpMock.expectOne('/super-admin/organizations/o1/archive').flush({});
    expect(hospitals.forgetFirstPage).toHaveBeenCalledTimes(1);
  });

  it('leaves reads and unrelated writes alone', () => {
    http.get('/super-admin/hospitals/search?q=').subscribe();
    httpMock.expectOne('/super-admin/hospitals/search?q=').flush([]);
    http.post('/patients', {}).subscribe();
    httpMock.expectOne('/patients').flush({});
    expect(hospitals.forgetFirstPage).not.toHaveBeenCalled();
  });
});
