import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { signal } from '@angular/core';

import { EmergencyBroadcastBannerComponent } from './emergency-broadcast-banner';
import {
  EmergencyBroadcastFrame,
  EmergencyBroadcastService,
} from '../services/emergency-broadcast.service';

interface MockBroadcastService {
  latest: ReturnType<typeof signal>;
  dismiss: jasmine.Spy;
}

describe('EmergencyBroadcastBannerComponent (MVP-7b)', () => {
  let service: MockBroadcastService;

  function setup(frame: EmergencyBroadcastFrame | null) {
    service.latest.set(frame);
    TestBed.configureTestingModule({
      imports: [EmergencyBroadcastBannerComponent, TranslateModule.forRoot()],
      providers: [{ provide: EmergencyBroadcastService, useValue: service }],
    });
    const fixture = TestBed.createComponent(EmergencyBroadcastBannerComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    service = {
      latest: signal(null),
      dismiss: jasmine.createSpy('dismiss'),
    };
  });

  afterEach(() => TestBed.resetTestingModule());

  it('hides when no broadcast is active', () => {
    const fixture = setup(null);
    expect(fixture.nativeElement.querySelector('[data-test="emergency-broadcast"]')).toBeNull();
  });

  it('renders the broadcast message with the issuer when a frame is active', () => {
    const fixture = setup({
      type: 'EMERGENCY_BROADCAST',
      severity: 'WARN',
      message: 'Maintenance window starts at 22:00',
      issuedBy: 'super.admin',
      issuedAt: '2026-05-03T18:00:00Z',
    });

    const banner = fixture.nativeElement.querySelector('[data-test="emergency-broadcast"]');
    expect(banner).not.toBeNull();
    expect(banner.textContent).toContain('Maintenance window starts at 22:00');
    expect(banner.textContent).toContain('super.admin');
  });

  it('severityClass() picks critical / warn / info from the frame', () => {
    let fixture = setup({ severity: 'CRITICAL', message: 'm' });
    expect(fixture.componentInstance.severityClass()).toBe('severity-critical');
    TestBed.resetTestingModule();

    service = {
      latest: signal({ severity: 'WARN', message: 'm' }),
      dismiss: jasmine.createSpy('dismiss'),
    };
    fixture = setup({ severity: 'WARN', message: 'm' });
    expect(fixture.componentInstance.severityClass()).toBe('severity-warn');
    TestBed.resetTestingModule();

    service = {
      latest: signal({ severity: 'INFO', message: 'm' }),
      dismiss: jasmine.createSpy('dismiss'),
    };
    fixture = setup({ severity: 'INFO', message: 'm' });
    expect(fixture.componentInstance.severityClass()).toBe('severity-info');
  });

  it('severityClass() defaults to info when severity is missing', () => {
    const fixture = setup({ message: 'no severity' });
    expect(fixture.componentInstance.severityClass()).toBe('severity-info');
  });

  it('dismiss() delegates to the service', () => {
    const fixture = setup({ message: 'whatever' });
    fixture.componentInstance.dismiss();
    expect(service.dismiss).toHaveBeenCalledTimes(1);
  });
});
