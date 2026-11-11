import { ApplicationInitStatus } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { appConfig } from './app.config';
import { OidcAuthService } from './auth/oidc-auth.service';
import { focusRescueInstalledOn, uninstallFocusRescue } from './shared/a11y/focus-rescue';

/**
 * QA D505: the comma-decimal fix lives entirely in one app initializer;
 * this spec runs the real appConfig providers so removing that line fails.
 * A detached document keeps the listeners off the shared Karma page.
 */
describe('appConfig start-up (QA D505 wiring)', () => {
  it('installs comma-decimal support on the document', async () => {
    const doc = document.implementation.createHTMLDocument('d505');
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        { provide: DOCUMENT, useValue: doc },
        { provide: OidcAuthService, useValue: { initialize: () => Promise.resolve() } },
      ],
    });
    await TestBed.inject(ApplicationInitStatus).donePromise;

    const input = doc.createElement('input');
    input.type = 'number';
    doc.body.appendChild(input);
    const event = new KeyboardEvent('keydown', { key: ',', bubbles: true, cancelable: true });
    input.dispatchEvent(event);

    expect(event.defaultPrevented).toBeTrue();
  });

  it('QA D1202: installs the focus rescue on the document', async () => {
    const doc = document.implementation.createHTMLDocument('d1202');
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        { provide: DOCUMENT, useValue: doc },
        { provide: OidcAuthService, useValue: { initialize: () => Promise.resolve() } },
      ],
    });
    await TestBed.inject(ApplicationInitStatus).donePromise;
    expect(focusRescueInstalledOn()).toBe(doc);
    uninstallFocusRescue();
  });
});
