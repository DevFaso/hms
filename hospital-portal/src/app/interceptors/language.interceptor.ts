import { HttpInterceptorFn } from '@angular/common/http';
import { storedLang } from '../shared/i18n/app-locale';

/**
 * Sends the language the user chose in the app — not the one their browser
 * happens to advertise.
 *
 * The backend resolves every message through `AcceptHeaderLocaleResolver`, so
 * messages_fr.properties, the Bean Validation messages and the localised PDF
 * labels are only ever used when the request says `Accept-Language: fr`. The
 * browser sends its own preference by default; on a laptop set up in English
 * that meant a clinician working in French got English error toasts from the
 * API while every label around them was French.
 */
export const languageInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ setHeaders: { 'Accept-Language': storedLang() } }));
