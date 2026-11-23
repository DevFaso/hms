import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Router, NavigationStart, NavigationEnd, NavigationCancel, Event } from '@angular/router';
import { Subject } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { RouteLoadingComponent } from './route-loading.component';

describe('Route loading feedback', () => {
  let fixture: ComponentFixture<RouteLoadingComponent>;
  let events: Subject<Event>;
  beforeEach(() => {
    events = new Subject<Event>();
    TestBed.configureTestingModule({
      imports: [RouteLoadingComponent, TranslateModule.forRoot()],
      providers: [{ provide: Router, useValue: { navigated: false, events } }],
    });
  });
  function create() {
    fixture = TestBed.createComponent(RouteLoadingComponent);
    fixture.detectChanges();
  }
  afterEach(() => fixture.destroy());
  it('fills the initially empty outlet and reports a slow connection after twenty seconds', fakeAsync(() => {
    create();
    expect(fixture.nativeElement.querySelector('[role="status"]')).not.toBeNull();
    tick(20000);
    fixture.detectChanges();
    expect(fixture.componentInstance.slow()).toBeTrue();
    events.next(new NavigationEnd(1, '/patients', '/patients'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
  }));
  it('an older cancellation cannot hide a newer navigation and it never reloads an open page', fakeAsync(() => {
    create();
    events.next(new NavigationEnd(1, '/patients', '/patients'));
    events.next(new NavigationStart(2, '/encounters'));
    events.next(new NavigationCancel(1, '/patients', 'redirect'));
    expect(fixture.componentInstance.loading()).toBeTrue();
    expect(fixture.componentInstance.initial()).toBeFalse();
    tick(20000);
    expect(fixture.componentInstance.slow()).toBeTrue();
    events.next(new NavigationEnd(2, '/encounters', '/encounters'));
    expect(fixture.componentInstance.loading()).toBeFalse();
  }));
});
