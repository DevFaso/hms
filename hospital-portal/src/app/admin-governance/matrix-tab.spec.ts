import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { MatrixTabComponent } from './matrix-tab';
import { ToastService } from '../core/toast.service';

describe('MatrixTabComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MatrixTabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  function latestRequest() {
    return http.expectOne((req) => req.url.includes('/permissions/matrix/snapshots/latest'));
  }

  function flushSideLists(): void {
    http.match((req) => !req.url.includes('/snapshots/latest')).forEach((req) => req.flush([]));
  }

  it('shows the empty state, not an error, when the latest snapshot answers 204 (D248)', () => {
    const component = TestBed.createComponent(MatrixTabComponent).componentInstance;
    component.ngOnInit();
    latestRequest().flush(null, { status: 204, statusText: 'No Content' });
    flushSideLists();

    expect(component.latest()).toBeNull();
    expect(component.latestEmpty()).toBeTrue();
    expect(component.latestError()).toBeFalse();
    expect(component.latestLoading()).toBeFalse();
  });

  it('a server failure is an error, not the empty state', () => {
    const component = TestBed.createComponent(MatrixTabComponent).componentInstance;
    component.ngOnInit();
    latestRequest().flush(null, { status: 500, statusText: 'Server Error' });
    flushSideLists();

    expect(component.latestEmpty()).toBeFalse();
    expect(component.latestError()).toBeTrue();
  });
});
