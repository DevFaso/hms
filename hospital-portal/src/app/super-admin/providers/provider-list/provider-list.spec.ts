import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ProviderPage } from '../../../services/provider.model';
import { SuperAdminProviderService } from '../../../services/super-admin-provider.service';
import { providerFixture } from '../../../testing/provider-fixtures';
import { lifecycleBadgeClass, verificationBadgeClass } from '../provider-status';
import { ProviderListComponent } from './provider-list';

function page(count: number, totalPages = 1, number = 0): ProviderPage {
  return {
    content: Array.from({ length: count }, (_, i) => providerFixture({ id: `p-${i}` })),
    totalElements: count,
    totalPages,
    size: 20,
    number,
  };
}

describe('ProviderListComponent', () => {
  let service: jasmine.SpyObj<SuperAdminProviderService>;

  function setup(): { cmp: ProviderListComponent; host: HTMLElement } {
    TestBed.configureTestingModule({
      imports: [ProviderListComponent, TranslateModule.forRoot()],
      providers: [provideRouter([]), { provide: SuperAdminProviderService, useValue: service }],
    });
    const fixture = TestBed.createComponent(ProviderListComponent);
    fixture.detectChanges();
    return { cmp: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<SuperAdminProviderService>('SuperAdminProviderService', [
      'list',
    ]);
    service.list.and.returnValue(of(page(2)));
  });

  it('lists every provider facility, unfiltered, on open', () => {
    const { host } = setup();
    expect(service.list).toHaveBeenCalledWith({ type: '', status: '' }, 0, 20);
    expect(host.querySelectorAll('[data-test="provider-row"]').length).toBe(2);
  });

  it('filters by type and status, back on the first page', () => {
    const { cmp } = setup();
    cmp.pageIndex.set(3);
    cmp.setType('LABORATORY');
    expect(service.list).toHaveBeenCalledWith({ type: 'LABORATORY', status: '' }, 0, 20);
    cmp.setStatus('VERIFIED');
    expect(service.list).toHaveBeenCalledWith({ type: 'LABORATORY', status: 'VERIFIED' }, 0, 20);
  });

  it('pages forward and back only where a page exists', () => {
    service.list.and.returnValue(of(page(1, 2)));
    const { cmp } = setup();
    cmp.previousPage();
    expect(cmp.pageIndex()).toBe(0);
    cmp.nextPage();
    expect(cmp.pageIndex()).toBe(1);
    cmp.nextPage();
    expect(cmp.pageIndex()).toBe(1);
    cmp.previousPage();
    expect(cmp.pageIndex()).toBe(0);
  });

  it('shows the empty state and the error state', () => {
    service.list.and.returnValue(of(page(0)));
    const empty = setup();
    expect(empty.host.querySelector('[data-test="empty"]')).not.toBeNull();
    TestBed.resetTestingModule();

    service.list.and.returnValue(throwError(() => new Error('down')));
    const failed = setup();
    expect(failed.host.querySelector('[data-test="error"]')).not.toBeNull();
    expect(failed.cmp.page()).toBeNull();
  });

  it('maps statuses and lifecycle states to the shared badge classes', () => {
    expect(verificationBadgeClass('VERIFIED')).toBe('active');
    expect(verificationBadgeClass('SUBMITTED')).toBe('in-progress');
    expect(verificationBadgeClass('REJECTED')).toBe('inactive');
    expect(verificationBadgeClass('REVOKED')).toBe('cancelled');
    expect(verificationBadgeClass(null)).toBe('');
    expect(lifecycleBadgeClass('ACTIVE')).toBe('active');
    expect(lifecycleBadgeClass('SUSPENDED')).toBe('in-progress');
    expect(lifecycleBadgeClass('ARCHIVED')).toBe('scheduled');
    expect(lifecycleBadgeClass('PENDING_PURGE')).toBe('inactive');
    expect(lifecycleBadgeClass('PURGED')).toBe('inactive');
    expect(lifecycleBadgeClass(undefined)).toBe('');
  });
});
