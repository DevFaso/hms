import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { FeatureFlagsComponent } from './feature-flags';

describe('FeatureFlagsComponent', () => {
  let component: FeatureFlagsComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [FeatureFlagsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
      ],
    });
    component = TestBed.createComponent(FeatureFlagsComponent).componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  const flag = { key: 'beta.x', enabled: true, editing: false, description: '' };

  it('does not delete an override the admin did not confirm (D126)', () => {
    spyOn(globalThis, 'confirm').and.returnValue(false);
    component.removeOverride(flag);
    httpMock.expectNone((r) => r.method === 'DELETE');
  });

  it('deletes the override once confirmed', () => {
    spyOn(globalThis, 'confirm').and.returnValue(true);
    component.removeOverride(flag);
    httpMock.expectOne((r) => r.method === 'DELETE' && r.url === '/feature-flags/beta.x');
  });

  it('says "no flags" with no empty quoted query when nothing is searched (D247)', () => {
    const fixture = TestBed.createComponent(FeatureFlagsComponent);
    fixture.detectChanges();
    httpMock.expectOne('/feature-flags').flush({});
    httpMock.match('/feature-flags/overrides').forEach((r) => r.flush([]));
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).querySelector('.empty-state')?.textContent;
    expect(text).toContain('FLAGS.NO_FLAGS');
    expect(text).not.toContain('""');
  });
});
