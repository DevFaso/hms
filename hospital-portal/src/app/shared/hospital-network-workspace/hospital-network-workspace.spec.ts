import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';
import { HospitalNetworkWorkspaceComponent } from './hospital-network-workspace';
import { HospitalService, HospitalResponse } from '../../services/hospital.service';

const hospitals = [
  { id: 'h1', name: 'Cdx North', city: 'North', code: 'CDX-N', active: true },
  { id: 'h2', name: 'Cdx South', city: 'South', code: 'CDX-S', active: false },
] as HospitalResponse[];
describe('HospitalNetworkWorkspaceComponent', () => {
  let c: HospitalNetworkWorkspaceComponent;
  let svc: jasmine.SpyObj<HospitalService>;
  let router: Router;
  beforeEach(() => {
    svc = jasmine.createSpyObj<HospitalService>('HospitalService', ['list']);
    svc.list.and.returnValue(of(hospitals));
    TestBed.configureTestingModule({
      imports: [HospitalNetworkWorkspaceComponent, TranslateModule.forRoot()],
      providers: [provideRouter([]), { provide: HospitalService, useValue: svc }],
    });
    router = TestBed.inject(Router);
    c = TestBed.createComponent(HospitalNetworkWorkspaceComponent).componentInstance;
  });
  it('shows and searches all hospitals without firing module APIs', () => {
    c.ngOnInit();
    expect(c.filtered().length).toBe(2);
    c.search.set('south');
    expect(c.filtered().map((h) => h.id)).toEqual(['h2']);
    expect(svc.list).toHaveBeenCalledTimes(1);
    c.activeOnly.set(true);
    expect(c.filtered()).toEqual([]);
    c.reset();
    expect(c.filtered().length).toBe(2);
  });
  it('renders a bounded page while retaining the complete directory count', () => {
    svc.list.and.returnValue(
      of(
        Array.from({ length: 43 }, (_, i) => ({
          ...hospitals[0],
          id: 'h' + i,
          name: 'Cdx ' + String(i).padStart(2, '0'),
        })),
      ),
    );
    c.load();
    expect(c.filtered().length).toBe(43);
    expect(c.visibleRows().length).toBe(20);
    c.page.set(2);
    expect(c.visibleRows().length).toBe(3);
  });

  it('opens the current module for the row hospital while preserving other URL state', () => {
    c.ngOnInit();
    spyOnProperty(router, 'url', 'get').and.returnValue('/registries?program=HIV#followup');
    const nav = spyOn(router, 'navigateByUrl').and.resolveTo(true);
    c.openHospital(hospitals[1]);
    expect(router.serializeUrl(nav.calls.mostRecent().args[0] as any)).toBe(
      '/registries?program=HIV&hospitalId=h2#followup',
    );
  });
  it('does not navigate for a hospital outside the loaded directory', () => {
    c.ngOnInit();
    const nav = spyOn(router, 'navigateByUrl').and.resolveTo(true);
    c.openHospital({ id: 'unknown' } as HospitalResponse);
    expect(nav).not.toHaveBeenCalled();
  });
  it('distinguishes a failed directory from an empty directory and supports retry', () => {
    svc.list.and.returnValues(
      throwError(() => new Error('offline')),
      of([]),
    );
    c.load();
    expect(c.failed()).toBeTrue();
    c.load();
    expect(c.failed()).toBeFalse();
    expect(c.hospitals()).toEqual([]);
  });
  it('ignores an earlier directory read after retrying', () => {
    const old = new Subject<HospitalResponse[]>();
    svc.list.and.returnValues(old, of([hospitals[1]]));
    c.load();
    c.load();
    old.next([hospitals[0]]);
    expect(c.hospitals().map((h) => h.id)).toEqual(['h2']);
  });
});
