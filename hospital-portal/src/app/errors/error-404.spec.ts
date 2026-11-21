import { Location } from '@angular/common';
import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, Routes } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { routes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { Error404Component } from './error-404';
import { unknownAddressRedirect } from './unknown-address.redirect';

@Component({ template: 'home', changeDetection: ChangeDetectionStrategy.OnPush })
class HomeStub {}

/**
 * QA D2802 / G1 (r65): an unknown address opens a "page not found" page, in
 * the reader's language, instead of landing silently on the dashboard.
 */
describe('Error404Component (QA D2802)', () => {
  it('says the page was not found, in French, with one title and a way back', async () => {
    await TestBed.configureTestingModule({
      imports: [Error404Component, TranslateModule.forRoot()],
      providers: [provideRouter([])],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      ERROR_404: {
        TITLE: 'Page introuvable',
        BODY: "Cette page n'existe pas ou a été déplacée.",
        BACK: 'Retour au tableau de bord',
      },
    });
    translate.use('fr');
    const fixture = TestBed.createComponent(Error404Component);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    const titles = el.querySelectorAll('h1');
    expect(titles.length).toBe(1);
    expect(titles[0].textContent?.trim()).toBe('Page introuvable');
    expect(el.textContent).toContain("Cette page n'existe pas");
    const back = el.querySelector<HTMLAnchorElement>('a.btn-primary')!;
    expect(back.getAttribute('href')).toBe('/dashboard');
    expect(back.textContent).toContain('Retour au tableau de bord');
    // No English, and the icons are not read aloud.
    expect(el.textContent).not.toContain('Not Found');
    for (const icon of Array.from(el.querySelectorAll('.material-symbols-outlined'))) {
      expect(icon.getAttribute('aria-hidden')).toBe('true');
    }
  });

  it('the catch-all route of the application is the unknown-address redirect, and 404 is a page of the shell', () => {
    const last = routes[routes.length - 1];
    expect(last.path).toBe('**');
    expect(last.redirectTo).toBe(unknownAddressRedirect);
    const shell = routes.find((r) => r.children?.some((c) => c.path === 'error/404'));
    expect(shell).withContext('error/404 is a page inside the shell').toBeDefined();
  });

  /** The application's own catch-all, over stub pages (no shell), signed in or out. */
  async function openUnknownAddress(signedIn: boolean): Promise<string> {
    const catchAll = routes[routes.length - 1];
    const stubRoutes: Routes = [
      { path: 'dashboard', component: HomeStub },
      { path: 'login', component: HomeStub },
      { path: 'error/404', component: Error404Component },
      { path: catchAll.path, redirectTo: catchAll.redirectTo },
    ];
    await TestBed.configureTestingModule({
      imports: [TranslateModule.forRoot()],
      providers: [
        provideRouter(stubRoutes),
        { provide: AuthService, useValue: { isAuthenticated: () => signedIn } },
      ],
    }).compileComponents();
    await TestBed.inject(Router).navigateByUrl('/this-route-does-not-exist');
    return TestBed.inject(Location).path();
  }

  it('signed in, an unknown address ends on the not-found page', async () => {
    expect(await openUnknownAddress(true)).toBe('/error/404');
  });

  it('signed out, it ends on the login page with no return address (never « page introuvable » after sign-in)', async () => {
    // Through /error/404 the shell's AuthGuard would keep it as the returnUrl.
    expect(await openUnknownAddress(false)).toBe('/login');
  });
});
