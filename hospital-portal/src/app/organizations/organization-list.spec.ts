import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';

import { OrganizationListComponent } from './organization-list';
import {
  OrganizationPage,
  OrganizationResponse,
  OrganizationService,
} from '../services/organization.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';

/**
 * The Type column used to render `formatType(org.type)` — "Community Hospital"
 * — so searching for a word on screen also matched the wire token behind it.
 * Translating the column broke that for every language but English: the cell
 * reads « Hôpital communautaire » while the filter still compared
 * COMMUNITY_HOSPITAL, so a French user typed a word that was visibly on the row
 * and got an empty list.
 */
describe('OrganizationListComponent — searching by the type on screen', () => {
  let fixture: ComponentFixture<OrganizationListComponent>;
  let component: OrganizationListComponent;
  let translate: TranslateService;
  let orgSpy: jasmine.SpyObj<OrganizationService>;
  let toast: jasmine.SpyObj<ToastService>;

  function org(overrides: Partial<OrganizationResponse> = {}): OrganizationResponse {
    return {
      id: 'o-1',
      name: 'Clinique du Plateau',
      code: 'CDP',
      type: 'COMMUNITY_HOSPITAL',
      ...overrides,
    } as OrganizationResponse;
  }

  beforeEach(async () => {
    orgSpy = jasmine.createSpyObj<OrganizationService>('OrganizationService', ['list', 'getTypes']);
    orgSpy.list.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }),
    );
    orgSpy.getTypes.and.returnValue(of([]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['error']);

    await TestBed.configureTestingModule({
      imports: [OrganizationListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: OrganizationService, useValue: orgSpy },
        { provide: ToastService, useValue: toast },
        // The shared stub, not a hand-rolled object: the component reads
        // `isSuperAdmin` at construction and the template calls it, which the
        // two-property double this spec used to carry did not provide. It
        // passed only because nothing here ever rendered.
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: true, hospitalId: null, roles: [] }),
        },
      ],
    }).compileComponents();

    translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { ORGANIZATION_TYPE: { COMMUNITY_HOSPITAL: 'Hôpital communautaire' } } },
    });
    translate.setTranslation('en', {
      PORTAL: { ENUM: { ORGANIZATION_TYPE: { COMMUNITY_HOSPITAL: 'Community Hospital' } } },
    });

    fixture = TestBed.createComponent(OrganizationListComponent);
    component = fixture.componentInstance;
    // The list ngOnInit loads is empty, so the rows below are set after it
    // rather than through the spy.
    fixture.detectChanges();
    component.organizations.set([
      org(),
      org({ id: 'o-2', name: 'CHU Yalgado', code: 'CHUY', type: 'HOSPITAL' }),
    ]);
  });

  it('matches the French label the Type column actually shows', () => {
    component.searchTerm.set('communautaire');
    expect(component.filtered().map((o) => o.id)).toEqual(['o-1']);
  });

  it('still matches the wire token, for anyone who knows it', () => {
    component.searchTerm.set('COMMUNITY_HOSPITAL');
    expect(component.filtered().map((o) => o.id)).toEqual(['o-1']);
  });

  it('still matches name and code', () => {
    component.searchTerm.set('yalgado');
    expect(component.filtered().map((o) => o.id)).toEqual(['o-2']);

    component.searchTerm.set('cdp');
    expect(component.filtered().map((o) => o.id)).toEqual(['o-1']);
  });

  it('re-runs the filter when the language changes', () => {
    // Matching a translated label means the matching rows change with the
    // language. Before the filter depended on the label revision the list went
    // stale: the row stayed listed under a French term while the cell read
    // English, and the row that would now match stayed hidden until the next
    // keystroke.
    component.searchTerm.set('communautaire');
    expect(component.filtered().map((o) => o.id)).toEqual(['o-1']);

    // Both switches below re-filter on their own: nothing but the language moves.
    translate.use('en');
    expect(component.filtered()).toEqual([]);

    translate.use('fr');
    expect(component.filtered().map((o) => o.id)).toEqual(['o-1']);
  });

  it('re-filters when the rows change, with no refresh call', () => {
    component.searchTerm.set('yalgado');
    expect(component.filtered()).toEqual([
      org({ id: 'o-2', name: 'CHU Yalgado', code: 'CHUY', type: 'HOSPITAL' }),
    ]);

    component.organizations.set([org()]);
    expect(component.filtered()).toEqual([]);
  });

  it('ignores a list response that lands after the page is gone', () => {
    // Navigating away mid-request used to let the response write the signals
    // (and an error toast a page nobody was on).
    const late = new Subject<OrganizationPage>();
    orgSpy.list.and.returnValue(late);
    const before = component.organizations();
    component.loadOrganizations();
    fixture.destroy();

    late.next({
      content: [org({ id: 'o-9' })],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
    });
    expect(component.organizations()).toBe(before);
    expect(component.loading()).toBeTrue();
  });

  it('does not toast a list failure that lands after the page is gone', () => {
    const late = new Subject<OrganizationPage>();
    orgSpy.list.and.returnValue(late);
    component.loadOrganizations();
    fixture.destroy();

    late.error(new Error('500'));
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('ignores a types response that lands after the page is gone', () => {
    const late = new Subject<string[]>();
    orgSpy.getTypes.and.returnValue(late);
    const second = TestBed.createComponent(OrganizationListComponent);
    second.detectChanges();
    second.destroy();

    late.next(['HOSPITAL']);
    expect(second.componentInstance.orgTypes()).toEqual([]);
  });
});
