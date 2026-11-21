import { TestBed } from '@angular/core/testing';
import { provideRouter, Route } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuditTabsComponent, SUPER_ADMIN_AUDIT_TABS } from './audit-tabs.component';
import { routes as appRoutes } from '../app.routes';
import { RoleContextService } from '../core/role-context.service';

/** QA r65 D2777: the super-admin's two audit screens are two tabs of one sidebar entry. */
describe('AuditTabsComponent (QA r65 D2777)', () => {
  function render(activeRole: string | null, superAdmin = false): HTMLElement {
    TestBed.configureTestingModule({
      imports: [AuditTabsComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter(appRoutes),
        { provide: RoleContextService, useValue: { activeRole, isSuperAdmin: () => superAdmin } },
      ],
    });
    const fixture = TestBed.createComponent(AuditTabsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => TestBed.resetTestingModule());

  it('a super-admin sees both audit screens as tabs, each one a real link', () => {
    const links = Array.from(render('ROLE_SUPER_ADMIN').querySelectorAll('a.audit-tab'));
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      '/super-admin/audit-logs',
      '/super-admin/audit-search',
    ]);
  });

  it('a hospital admin, whose log page has no search screen beside it, sees no tabs', () => {
    expect(render('ROLE_HOSPITAL_ADMIN').querySelector('[data-testid="audit-tabs"]')).toBeNull();
  });

  it('a super-admin acting in another role sees no tabs (the acting role decides)', () => {
    expect(render('ROLE_DOCTOR', true).querySelector('[data-testid="audit-tabs"]')).toBeNull();
  });

  it('every tab opens a route that exists and admits the super-admin alone', () => {
    function find(path: string, table: Route[]): Route | undefined {
      for (const route of table) {
        if ('/' + (route.path ?? '') === path && route.loadComponent) return route;
        const nested = route.children ? find(path, route.children) : undefined;
        if (nested) return nested;
      }
      return undefined;
    }
    for (const tab of SUPER_ADMIN_AUDIT_TABS) {
      const route = find(tab.route, appRoutes);
      expect(route).withContext(tab.route).toBeDefined();
      expect(route?.data?.['roles']).withContext(tab.route).toEqual(['ROLE_SUPER_ADMIN']);
    }
  });
});
