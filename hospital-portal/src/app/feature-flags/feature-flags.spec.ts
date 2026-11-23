import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { FeatureFlagsComponent } from './feature-flags';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

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

  it('D2769: known flags have readable labels and supplied descriptions stay authoritative', () => {
    const flag = { key: 'ff.platform.billing', enabled: true, editing: false, description: '' };
    expect(component.flagTitle(flag)).toBe('FLAGS.CATALOG.BILLING_TITLE');
    expect(component.flagDescription(flag)).toBe('FLAGS.CATALOG.BILLING_DESCRIPTION');
    component.overrides.set([
      {
        id: 'override',
        flagKey: flag.key,
        enabled: true,
        description: 'Description locale validée',
        updatedBy: 'cdx-admin',
        updatedAt: '2026-11-22',
      },
    ]);
    expect(component.flagDescription(flag)).toBe('Description locale validée');
    const custom = { ...flag, key: 'hospital.custom' };
    expect(component.flagTitle(custom)).toBe('hospital.custom');
    expect(component.flagDescription(custom)).toBe('FLAGS.NO_DESCRIPTION');
  });

  it('D2769: the configure dialog offers only keys declared by the server', () => {
    const fixture = TestBed.createComponent(FeatureFlagsComponent);
    fixture.detectChanges();
    httpMock.expectOne('/feature-flags').flush({ 'known.flag': true });
    httpMock.match('/feature-flags/overrides').forEach((r) => r.flush([]));
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('select#flag-key') as HTMLSelectElement;
    expect(select).not.toBeNull();
    expect(Array.from(select.options).map((option) => option.value)).toEqual(['', 'known.flag']);
    expect(fixture.nativeElement.querySelector('input#flag-key')).toBeNull();
  });

  it('does not delete an override the admin did not confirm (D126)', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);
    await component.removeOverride(flag);
    httpMock.expectNone((r) => r.method === 'DELETE');
  });

  it('deletes the override once confirmed', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    await component.removeOverride(flag);
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

  it('shows the server reason when a flag cannot be created (QA D310)', () => {
    const toast = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;
    component.newFlagKey.set('qa.r6.newflag');
    component.createFlag();
    httpMock
      .expectOne((r) => r.method === 'PUT' && r.url === '/feature-flags/qa.r6.newflag')
      .flush(
        { message: 'Drapeau de fonctionnalité inconnu.' },
        { status: 400, statusText: 'Bad Request' },
      );
    expect(toast.error).toHaveBeenCalledWith('Drapeau de fonctionnalité inconnu.');
  });

  it('QA CX-004: an empty key is marked, starred, and nothing is sent', () => {
    const fixture = TestBed.createComponent(FeatureFlagsComponent);
    component = fixture.componentInstance;
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    httpMock.expectOne('/feature-flags').flush({});
    httpMock.match('/feature-flags/overrides').forEach((r) => r.flush([]));
    component.openCreate();
    fixture.detectChanges();

    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="flag-create-save"]',
    });

    expect(marked).toEqual(['flag-key']);
    httpMock.expectNone((r) => r.method === 'PUT');
    (fixture.nativeElement as HTMLElement).remove();
  });
});
