import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { LabTestConfigComponent } from './lab-test-config';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';

/**
 * QA D2730 (review): a test's sensitive category could be set through the API
 * only. The lab director sets it in the definition editor; nobody else is
 * offered it, and nobody else's save names one.
 */
describe('LabTestConfigComponent - the sensitive category of a test (QA D2730)', () => {
  let fixture: ComponentFixture<LabTestConfigComponent>;
  let component: LabTestConfigComponent;
  let httpMock: HttpTestingController;

  const definition = (overrides: Record<string, unknown> = {}) => ({
    id: 'def-1',
    testCode: 'TPHA',
    testName: 'Sérologie syphilis (TPHA)',
    description: '',
    category: 'Sérologie',
    sampleType: 'Blood',
    unit: '',
    isActive: true,
    approvalStatus: 'ACTIVE',
    referenceRanges: [],
    ...overrides,
  });

  function setup(roles: string[], content: Record<string, unknown>[]): void {
    TestBed.configureTestingModule({
      imports: [LabTestConfigComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles }),
        },
      ],
    });
    fixture = TestBed.createComponent(LabTestConfigComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    flushSearch(content);
    httpMock
      .match((r) => r.url.includes('/assignments'))
      .forEach((r) => r.flush([{ id: 'assign-1', active: true }]));
    fixture.detectChanges();
  }

  function flushSearch(content: Record<string, unknown>[]): void {
    httpMock
      .expectOne((r) => r.url.includes('/lab-test-definitions/search'))
      .flush({ data: { content, totalElements: content.length, totalPages: 1, number: 0 } });
  }

  function saveAndReadBody(): Record<string, unknown> {
    component.submitDefForm();
    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab-test-definitions/def-1') && r.method === 'PUT',
    );
    const body = req.request.body as Record<string, unknown>;
    req.flush({ data: definition(), success: true });
    flushSearch([definition()]);
    return body;
  }

  afterEach(() => {
    httpMock.match(() => true).forEach((r) => !r.cancelled && r.flush([]));
  });

  it('the director picks a category in the editor, and the save carries it', () => {
    setup(['ROLE_LAB_DIRECTOR'], [definition()]);
    component.openEditDef(component.definitions()[0]);
    fixture.detectChanges();
    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      '#defSensitivityCategory',
    )!;
    expect(select).not.toBeNull();
    expect(select.value).toBe('');
    select.value = 'REPRODUCTIVE_HEALTH';
    select.dispatchEvent(new Event('change'));

    const body = saveAndReadBody();
    expect(body['sensitivityCategory']).toBe('REPRODUCTIVE_HEALTH');
    expect(body['clearSensitivityCategory']).toBeUndefined();
  });

  it('the director removes a category with the explicit clear, never by leaving it out', () => {
    setup(['ROLE_LAB_DIRECTOR'], [definition({ sensitivityCategory: 'HIV' })]);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="def-sensitive"]')).not.toBeNull();
    component.openEditDef(component.definitions()[0]);
    fixture.detectChanges();
    const select = host.querySelector<HTMLSelectElement>('#defSensitivityCategory')!;
    expect(select.value).toBe('HIV');
    select.value = '';
    select.dispatchEvent(new Event('change'));

    const body = saveAndReadBody();
    expect(body['clearSensitivityCategory']).toBeTrue();
    expect(body['sensitivityCategory']).toBeUndefined();
  });

  it('a director who leaves the category as it was sends neither field', () => {
    setup(['ROLE_LAB_DIRECTOR'], [definition({ sensitivityCategory: 'HIV' })]);
    component.openEditDef(component.definitions()[0]);
    component.defForm.testName = 'Sérologie syphilis';

    const body = saveAndReadBody();
    expect(body['sensitivityCategory']).toBeUndefined();
    expect(body['clearSensitivityCategory']).toBeUndefined();
  });

  it('a lab scientist sees the tag in the catalogue, is offered no control, and sends no category', () => {
    setup(['ROLE_LAB_SCIENTIST'], [definition({ sensitivityCategory: 'HIV' })]);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="def-sensitive"]')).not.toBeNull();
    component.openEditDef(component.definitions()[0]);
    fixture.detectChanges();
    expect(host.querySelector('#defSensitivityCategory')).toBeNull();

    const body = saveAndReadBody();
    expect(body['sensitivityCategory']).toBeUndefined();
    expect(body['clearSensitivityCategory']).toBeUndefined();
  });
});
