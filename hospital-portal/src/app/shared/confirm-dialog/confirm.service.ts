import {
  ApplicationRef,
  ComponentRef,
  EnvironmentInjector,
  Injectable,
  createComponent,
  inject,
} from '@angular/core';

import { ConfirmDialogComponent } from './confirm-dialog.component';

/** What the confirmation says. Keys are translated by the dialog. */
export interface ConfirmRequest {
  /** The question, as the dialog's title. */
  titleKey: string;
  /** An optional sentence under the title. */
  messageKey?: string | null;
  /** An already-translated sentence under the title (a server's refusal). */
  message?: string | null;
  /** Parameters of the title and the message. */
  params?: Record<string, unknown> | null;
  /** The confirming button (defaults to "Confirmer"). */
  confirmKey?: string;
  /** The dismissing button (defaults to "Annuler"). */
  dismissKey?: string;
}

/**
 * QA D1229 (round 24): the portal asked "are you sure?" with the browser's own
 * `window.confirm` on ten screens (patient document delete, report signing,
 * death record, admin deletes…): a question in the browser's chrome, in the
 * language of the browser rather than the reader's, with no keyboard contract
 * (#1005/#1010) and no focus return. This is the one way to ask instead:
 *
 * ```ts
 * if (!(await this.confirm.ask({ titleKey: 'PORTAL.DOCUMENTS.DELETE_CONFIRM', confirmKey: 'COMMON.DELETE' }))) return;
 * ```
 *
 * It opens the shared `<app-confirm-dialog>` (a modal: focus on Cancel — the
 * safe choice — Escape and the backdrop cancel, focus back to the button that
 * opened it) and resolves `true` on confirm, `false` on cancel.
 * `scripts/lib/no-native-prompt.test.mjs` fails the build for any
 * `confirm(` / `alert(` / `prompt(` of the browser.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly appRef = inject(ApplicationRef);
  private readonly injector = inject(EnvironmentInjector);
  private open: ComponentRef<ConfirmDialogComponent> | null = null;

  ask(request: ConfirmRequest): Promise<boolean> {
    // One question at a time: a second ask while one is open is a "no".
    if (this.open) return Promise.resolve(false);
    return new Promise<boolean>((resolve) => {
      const host = document.createElement('div');
      document.body.appendChild(host);
      const ref = createComponent(ConfirmDialogComponent, {
        environmentInjector: this.injector,
        hostElement: host,
      });
      ref.setInput('titleKey', request.titleKey);
      ref.setInput('messageKey', request.messageKey ?? null);
      ref.setInput('params', request.params ?? null);
      ref.setInput('messageText', request.message ?? null);
      if (request.confirmKey) ref.setInput('confirmKey', request.confirmKey);
      if (request.dismissKey) ref.setInput('dismissKey', request.dismissKey);
      const finish = (answer: boolean): void => {
        confirmed.unsubscribe();
        cancelled.unsubscribe();
        this.appRef.detachView(ref.hostView);
        ref.destroy();
        host.remove();
        this.open = null;
        resolve(answer);
      };
      const confirmed = ref.instance.confirmed.subscribe(() => finish(true));
      const cancelled = ref.instance.cancelled.subscribe(() => finish(false));
      this.open = ref;
      this.appRef.attachView(ref.hostView);
      ref.changeDetectorRef.detectChanges();
    });
  }
}
