import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { WebhooksComponent } from './webhooks';
import {
  ApiKey,
  IntegrationKeysService,
  WebhookEndpoint,
} from '../services/integration-keys.service';
import { HospitalService } from '../services/hospital.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

function key(overrides: Partial<ApiKey> = {}): ApiKey {
  return {
    id: 'k1',
    label: 'Mutuelle X claims',
    keyPrefix: 'hms_pk_abcd',
    status: 'ACTIVE',
    ...overrides,
  };
}

function endpoint(overrides: Partial<WebhookEndpoint> = {}): WebhookEndpoint {
  return {
    id: 'e1',
    url: 'https://receiver.example/hook',
    status: 'ACTIVE',
    events: ['APPOINTMENT_BOOKED'],
    consecutiveFailures: 0,
    ...overrides,
  };
}

/**
 * API keys + webhooks admin (Tier 2 item 45). Pins: the raw key/secret
 * renders ONLY in the reveal dialog and closing it is final; an outage
 * renders "unavailable", never an empty credential inventory; a
 * global-view super-admin gets the pick-a-hospital state with zero
 * requests fired; and the switchMap race — unpinning mid-flight drops
 * the old hospital's response.
 */
describe('WebhooksComponent', () => {
  let fixture: ComponentFixture<WebhooksComponent>;
  let component: WebhooksComponent;
  let api: jasmine.SpyObj<IntegrationKeysService>;
  let toast: jasmine.SpyObj<ToastService>;
  // A signal, not a closure variable: the component reads scope inside a
  // computed(), which only re-evaluates on a signal dependency change.
  let scopedHospitalId: WritableSignal<string | null>;

  beforeEach(async () => {
    scopedHospitalId = signal<string | null>('h1');
    api = jasmine.createSpyObj<IntegrationKeysService>('IntegrationKeysService', [
      'listKeys',
      'issueKey',
      'rotateKey',
      'revokeKey',
      'listEndpoints',
      'registerEndpoint',
      'updateEndpoint',
      'setEndpointActive',
      'revokeEndpoint',
      'rotateEndpointSecret',
      'pingEndpoint',
      'deliveries',
    ]);
    api.listKeys.and.returnValue(of([key()]));
    api.listEndpoints.and.returnValue(of([endpoint()]));

    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);
    const hospitalSpy = jasmine.createSpyObj('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalSpy.list.and.returnValue(of([]));
    const scopeUrlSpy = jasmine.createSpyObj('HospitalScopeUrlService', ['applyUrlScopeSync']);

    await TestBed.configureTestingModule({
      imports: [WebhooksComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: IntegrationKeysService, useValue: api },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: HospitalScopeUrlService, useValue: scopeUrlSpy },
        {
          provide: RoleContextService,
          useValue: {
            effectiveHospitalIdForRequest: () => scopedHospitalId(),
            activeHospitalId: 'h1',
            isSuperAdmin: () => false,
            globalView: () => scopedHospitalId() === null,
            selectedHospitalId: () => scopedHospitalId(),
            permittedHospitalIds: ['h1'],
            hasAnyActiveRole: () => true,
          },
        },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(WebhooksComponent);
    component = fixture.componentInstance;
  });

  it('loads keys and endpoints on init', () => {
    fixture.detectChanges();
    expect(api.listKeys).toHaveBeenCalled();
    expect(component.keys().length).toBe(1);
    expect(component.endpoints().length).toBe(1);
  });

  it('a global-view super-admin gets the pick-a-hospital state — no requests fired', () => {
    scopedHospitalId.set(null);
    fixture.detectChanges();
    expect(api.listKeys).not.toHaveBeenCalled();
    expect(component.scopeReady()).toBeFalse();
  });

  it('an outage renders unavailable — never an empty credential inventory', () => {
    api.listKeys.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    fixture.detectChanges();
    expect(component.loadFailed()).toBeTrue();
    expect(component.keys().length).toBe(0);
  });

  it('unpinning the scope mid-flight drops the old hospital response', () => {
    const slow = new Subject<ApiKey[]>();
    api.listKeys.and.returnValue(slow.asObservable());
    fixture.detectChanges(); // load in flight

    scopedHospitalId.set(null);
    component.onScopeChange(null);
    slow.next([key()]);
    slow.complete();

    expect(component.keys().length).toBe(0);
  });

  it('issuing a key opens the one-time reveal, and closing it is final', () => {
    api.issueKey.and.returnValue(of({ key: key(), rawKey: 'hms_pk_theRawKeyOnce' }));
    fixture.detectChanges();
    component.openIssue();
    component.formKeyLabel.set('Mutuelle X claims');

    component.submitIssue();

    expect(component.revealed()?.value).toBe('hms_pk_theRawKeyOnce');
    component.closeReveal();
    expect(component.revealed()).toBeNull();
  });

  it('rotating a key goes through the confirm dialog and reveals the replacement once', () => {
    api.rotateKey.and.returnValue(of({ key: key({ id: 'k2' }), rawKey: 'hms_pk_replacement' }));
    fixture.detectChanges();
    component.openConfirm('rotate-key', 'k1', 'Mutuelle X claims');

    component.submitConfirm();

    expect(api.rotateKey).toHaveBeenCalledWith('k1');
    expect(component.revealed()?.value).toBe('hms_pk_replacement');
  });

  it('the endpoint form is invalid until a URL and at least one event are set', () => {
    fixture.detectChanges();
    component.openEndpointForm(null);
    expect(component.endpointFormValid()).toBeFalse();
    component.formUrl.set('https://receiver.example/hook');
    expect(component.endpointFormValid()).toBeFalse();
    component.toggleEvent('APPOINTMENT_BOOKED');
    expect(component.endpointFormValid()).toBeTrue();
  });

  it('registering an endpoint reveals the signing secret once', () => {
    api.registerEndpoint.and.returnValue(of({ endpoint: endpoint(), secret: 'whsec_once' }));
    fixture.detectChanges();
    component.openEndpointForm(null);
    component.formUrl.set('https://receiver.example/hook');
    component.toggleEvent('APPOINTMENT_BOOKED');

    component.submitEndpointForm();

    expect(component.revealed()?.value).toBe('whsec_once');
  });
});
