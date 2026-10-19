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
});
