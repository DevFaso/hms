import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

interface SettingsCard {
  icon: string;
  titleKey: string;
  descKey: string;
  route?: string;
  queryParams?: Record<string, string>;
  actionKey: string;
}

const SUPPORTED_LANGS = [
  { code: 'fr', labelKey: 'SETTINGS_HUB.LANG_FR' },
  { code: 'en', labelKey: 'SETTINGS_HUB.LANG_EN' },
  { code: 'es', labelKey: 'SETTINGS_HUB.LANG_ES' },
] as const;

/**
 * Settings hub — replaces the previous /settings → /profile redirect.
 *
 * The Profile page owns "who am I" (personal info, security tab, activity).
 * This page owns "how the app behaves for me" — preferences and shortcuts to
 * the existing settings sub-pages so a user has one obvious entry point
 * rather than two header links that quietly lead to the same screen.
 */
@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslateModule],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class SettingsComponent {
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  readonly languages = SUPPORTED_LANGS;
  currentLang = signal<string>(this.translate.currentLang || this.translate.defaultLang || 'fr');

  cards = computed<SettingsCard[]>(() => [
    {
      icon: 'manage_accounts',
      titleKey: 'SETTINGS_HUB.ACCOUNT_TITLE',
      descKey: 'SETTINGS_HUB.ACCOUNT_DESC',
      route: '/profile',
      queryParams: { tab: 'edit' },
      actionKey: 'SETTINGS_HUB.OPEN_PROFILE',
    },
    {
      icon: 'lock',
      titleKey: 'SETTINGS_HUB.SECURITY_TITLE',
      descKey: 'SETTINGS_HUB.SECURITY_DESC',
      route: '/profile',
      queryParams: { tab: 'security' },
      actionKey: 'SETTINGS_HUB.OPEN_SECURITY',
    },
    {
      icon: 'notifications_active',
      titleKey: 'SETTINGS_HUB.NOTIFICATIONS_TITLE',
      descKey: 'SETTINGS_HUB.NOTIFICATIONS_DESC',
      route: '/notification-settings',
      actionKey: 'SETTINGS_HUB.OPEN_NOTIFICATIONS',
    },
    {
      icon: 'history',
      titleKey: 'SETTINGS_HUB.ACTIVITY_TITLE',
      descKey: 'SETTINGS_HUB.ACTIVITY_DESC',
      route: '/profile',
      queryParams: { tab: 'activity' },
      actionKey: 'SETTINGS_HUB.OPEN_ACTIVITY',
    },
    {
      icon: 'shield',
      titleKey: 'SETTINGS_HUB.PRIVACY_TITLE',
      descKey: 'SETTINGS_HUB.PRIVACY_DESC',
      route: '/privacy-policy',
      actionKey: 'SETTINGS_HUB.OPEN_PRIVACY',
    },
  ]);

  switchLang(lang: string): void {
    this.translate.use(lang);
    this.currentLang.set(lang);
    try {
      localStorage.setItem('lang', lang);
    } catch {
      // localStorage can throw in privacy modes — preference is still applied for the session.
    }
  }
}
