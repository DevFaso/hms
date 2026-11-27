import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { InBasketPageComponent } from './in-basket-page';
import { DashboardService } from '../services/dashboard.service';
import { InBasketService } from '../services/in-basket.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { UrgentAlertsService } from '../services/urgent-alerts.service';
import { urgentAlertsStub } from '../testing/urgent-alerts.stub';

describe('InBasketPageComponent', () => {
  let fixture: ComponentFixture<InBasketPageComponent>;
  let component: InBasketPageComponent;

  function setup(roles: string[]): void {
    const dashboardService = jasmine.createSpyObj<DashboardService>('DashboardService', [
      'getResultReviewQueue',
    ]);
    dashboardService.getResultReviewQueue.and.returnValue(of([]));

    const inBasketService = jasmine.createSpyObj<InBasketService>('InBasketService', [
      'getItems',
      'getSummary',
    ]);
    inBasketService.getItems.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
    );
    inBasketService.getSummary.and.returnValue(
      of({
        totalUnread: 0,
        resultUnread: 0,
        orderUnread: 0,
        messageUnread: 0,
        taskUnread: 0,
      }),
    );

    TestBed.configureTestingModule({
      imports: [InBasketPageComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: DashboardService, useValue: dashboardService },
        { provide: InBasketService, useValue: inBasketService },
        {
          provide: UrgentAlertsService,
          useValue: urgentAlertsStub({
            mine: { toAcknowledge: 2, oldestRaisedAt: '2026-11-27T05:20:52', items: [] },
          }),
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles }),
        },
      ],
    });

    fixture = TestBed.createComponent(InBasketPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('should create', () => {
    setup(['ROLE_DOCTOR']);
    expect(component).toBeTruthy();
  });

  it('should render page header', () => {
    setup(['ROLE_DOCTOR']);
    expect(fixture.nativeElement.querySelector('.page-header h1')).toBeTruthy();
  });

  it('should contain in-basket panel', () => {
    setup(['ROLE_DOCTOR']);
    expect(fixture.nativeElement.querySelector('app-in-basket-panel')).toBeTruthy();
  });

  it('shows a single inbox heading on the full page', () => {
    setup(['ROLE_NURSE']);
    expect(fixture.nativeElement.querySelector('h1')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-in-basket-panel .panel-title')).toBeNull();
  });

  /* ── QA D4100 (r81): the urgent alerts first ── */

  it('puts the urgent alerts to acknowledge above the laboratory results and the item list', () => {
    setup(['ROLE_DOCTOR']);

    const page: HTMLElement = fixture.nativeElement.querySelector('.in-basket-page');
    const order = Array.from(page.children).map((child) => child.tagName.toLowerCase());
    const banner = order.indexOf('app-urgent-alerts-banner');
    expect(banner).toBeGreaterThan(-1);
    expect(banner).toBeLessThan(order.indexOf('app-lab-results-inbox'));
    expect(banner).toBeLessThan(order.indexOf('app-in-basket-panel'));
    expect(page.querySelector('[data-testid="urgent-alerts-count"]')).not.toBeNull();
  });

  it('shows the urgent alerts to a nurse and a midwife too (the same component, no results table)', () => {
    setup(['ROLE_MIDWIFE']);

    expect(
      fixture.nativeElement.querySelector('[data-testid="urgent-alerts-banner"]'),
    ).not.toBeNull();
  });

  /* ── B7: the lab-results category and its gate ── */

  it('shows the lab-results category to a doctor', () => {
    setup(['ROLE_DOCTOR']);

    expect(component.canReviewLabResults()).toBeTrue();
    expect(fixture.nativeElement.querySelector('app-lab-results-inbox')).not.toBeNull();
  });

  it('shows the lab-results category to a surgeon, whom the endpoint admits by name', () => {
    setup(['ROLE_SURGEON']);

    expect(fixture.nativeElement.querySelector('app-lab-results-inbox')).not.toBeNull();
  });

  it('hides the lab-results category from a nurse the endpoint refuses', () => {
    // /in-basket admits nurses; /me/results/review-queue does not. The
    // category must be absent rather than toast a 403 at someone who never
    // chose it.
    setup(['ROLE_NURSE']);

    expect(component.canReviewLabResults()).toBeFalse();
    expect(fixture.nativeElement.querySelector('app-lab-results-inbox')).toBeNull();
  });

  it('hides the lab-results category from a lab manager', () => {
    setup(['ROLE_LAB_MANAGER']);

    expect(fixture.nativeElement.querySelector('app-lab-results-inbox')).toBeNull();
  });
});
