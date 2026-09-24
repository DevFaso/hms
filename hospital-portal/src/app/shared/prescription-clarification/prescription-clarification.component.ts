import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EMPTY, Subject } from 'rxjs';
import { catchError, switchMap, tap } from 'rxjs/operators';

import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { PrescriptionService } from '../../services/prescription.service';

/** Which half of the exchange this instance offers. */
export type ClarificationMode = 'PHARMACY' | 'PRESCRIBER';

/**
 * The pharmacist-to-prescriber clarification exchange (gap G5), as one
 * control that can be dropped into a row's action cell.
 *
 * <p>Two halves of the same conversation, so one component rather than two:
 * the modal chrome, the 1000-character limit, the inline error state and the
 * reload-on-success contract are identical, and only the copy, the endpoint
 * and the role gate differ.
 *
 * <ul>
 *   <li><b>PHARMACY</b> (dispense work queue): raise a question against a
 *       prescription awaiting a fill, and read the prescriber's answer once
 *       the row comes back flagged {@code CLARIFICATION_RESOLVED}.</li>
 *   <li><b>PRESCRIBER</b> (prescriptions list): answer a question on a
 *       PENDING_CLARIFICATION row and send it back to the pharmacy.</li>
 * </ul>
 *
 * <p><b>Roles are read live</b> through {@link RoleContextService} rather
 * than snapshotted in the constructor, so a hospital-scope or active-role
 * change re-evaluates the gate. The lists below mirror the backend
 * {@code @PreAuthorize} exactly; whether the CALLER may act is still the
 * server's decision — it refuses a doctor with no staff profile at the
 * prescribing hospital, for instance.
 *
 * <p><b>What a refusal shows.</b> The workflow refusals
 * ({@code BusinessException}: wrong status, missing reason) arrive with a
 * message and are shown verbatim, because "this one is PENDING_CLARIFICATION"
 * tells the user what to do next and a generic string does not — those
 * sentences are composed in English on the server, which is a known gap in
 * the French-completeness layers rather than something this component can
 * fix. An authorization refusal is different: {@code GlobalExceptionHandler}
 * collapses every {@code AccessDeniedException} to the literal "Access
 * denied", which names neither the rule nor the remedy, so a 403 is replaced
 * by the localized sentence that does.
 *
 * <p><b>Nothing clinical is logged.</b> The question and the answer are free
 * clinical text: they are rendered and posted, never written to the console
 * and never composed into a toast.
 */
@Component({
  selector: 'app-prescription-clarification',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './prescription-clarification.component.html',
  styleUrl: './prescription-clarification.component.scss',
})
export class PrescriptionClarificationComponent {
  private readonly prescriptions = inject(PrescriptionService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /**
   * Roles the backend's {@code /request-clarification} endpoint admits.
   * One named list rather than a copy at each call site — drifting copies of
   * a role list are a recurring defect here.
   */
  static readonly REQUEST_ROLES = ['ROLE_PHARMACIST', 'ROLE_PHARMACY_VERIFIER', 'ROLE_SUPER_ADMIN'];

  /** {@code /resolve-clarification} is ROLE_DOCTOR only — no role hierarchy backs it. */
  static readonly RESOLVE_ROLES = ['ROLE_DOCTOR'];

  /**
   * Roles {@code GET /prescriptions/{id}} admits, which is the only way to
   * read the exchange text: the work-queue projection carries the flag but
   * not the words. PHARMACY_VERIFIER is deliberately absent — the endpoint
   * refuses it, so the component explains instead of firing a certain 403.
   */
  static readonly READ_ROLES = ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST'];

  /**
   * Statuses the backend lets a question be raised from. Mirrors
   * {@code PrescriptionClarificationService.CLARIFIABLE_STATUSES}; a status
   * outside it would be refused with a 400 the pharmacist can do nothing
   * about, so the button is simply not offered.
   */
  static readonly CLARIFIABLE_STATUSES = [
    'SIGNED',
    'TRANSMITTED',
    'PARTIALLY_FILLED',
    'PENDING_STOCK',
    'PARTNER_REJECTED',
  ];

  /** The work-queue attention flag that means "the prescriber has answered". */
  static readonly RESOLVED_FLAG = 'CLARIFICATION_RESOLVED';

  readonly mode = input.required<ClarificationMode>();
  readonly prescriptionId = input.required<string>();
  readonly status = input<string | null>(null);
  readonly medicationLabel = input<string | null>(null);

  /** PHARMACY mode: {@code attentionReason} straight off the work-queue row. */
  readonly attentionReason = input<string | null>(null);

  /** PRESCRIBER mode: the pharmacist's question, already on the list row. */
  readonly question = input<string | null>(null);
  readonly askedAt = input<string | null>(null);

  /** Emitted after a successful post so the host reloads its list. */
  readonly changed = output<void>();

  protected readonly open = signal(false);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  /** PHARMACY mode, fetched on open when the prescriber has answered. */
  protected readonly loadingExchange = signal(false);
  protected readonly exchangeError = signal(false);
  protected readonly fetchedQuestion = signal<string | null>(null);
  protected readonly fetchedAnswer = signal<string | null>(null);
  protected readonly fetchedAskedAt = signal<string | null>(null);
  protected readonly fetchedAnsweredAt = signal<string | null>(null);

  /** Bound to the single textarea; its meaning depends on {@link mode}. */
  protected text = '';

  private readonly dialog = viewChild<ElementRef<HTMLElement>>('dialog');
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  /** One in-flight exchange read at a time; a retry cancels its predecessor. */
  private readonly exchangeRequests = new Subject<void>();

  constructor() {
    // switchMap rather than a fresh subscription per click: two Retries in a
    // row otherwise leave two responses racing to write the same four
    // signals, and a late failure would hide an exchange that had loaded.
    // catchError sits INSIDE so a failure ends that attempt, not the stream.
    this.exchangeRequests
      .pipe(
        tap(() => {
          this.loadingExchange.set(true);
          this.exchangeError.set(false);
        }),
        switchMap(() =>
          this.prescriptions.getById(this.prescriptionId()).pipe(
            catchError(() => {
              // Explicit error state — never an empty exchange, which would
              // read as "the prescriber answered with nothing".
              this.loadingExchange.set(false);
              this.exchangeError.set(true);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((rx) => {
        this.fetchedQuestion.set(rx?.clarificationReason ?? null);
        this.fetchedAnswer.set(rx?.clarificationResponse ?? null);
        this.fetchedAskedAt.set(rx?.clarificationRequestedAt ?? null);
        this.fetchedAnsweredAt.set(rx?.clarificationResolvedAt ?? null);
        this.loadingExchange.set(false);
      });

    // Focus the dialog as it opens: without it, Escape is delivered to the
    // trigger button (which sits outside the backdrop subtree) and the key
    // handler on the dialog never runs, and a screen-reader user is left
    // outside the thing that just appeared.
    effect(() => {
      const host = this.dialog();
      if (this.open() && host) {
        host.nativeElement.focus();
      }
    });
  }

  protected readonly isPharmacy = computed(() => this.mode() === 'PHARMACY');

  /** True once the prescriber has answered and the row is back on the queue. */
  protected readonly hasAnswer = computed(
    () =>
      this.isPharmacy() &&
      this.attentionReason() === PrescriptionClarificationComponent.RESOLVED_FLAG,
  );

  protected readonly canRequest = computed(
    () =>
      this.isPharmacy() &&
      this.roleContext.hasAnyActiveRole(PrescriptionClarificationComponent.REQUEST_ROLES) &&
      PrescriptionClarificationComponent.CLARIFIABLE_STATUSES.includes(this.status() ?? ''),
  );

  protected readonly canResolve = computed(
    () =>
      !this.isPharmacy() &&
      this.roleContext.hasAnyActiveRole(PrescriptionClarificationComponent.RESOLVE_ROLES) &&
      this.status() === 'PENDING_CLARIFICATION',
  );

  /**
   * Whether this user may read the exchange text at all. Separate from
   * {@link canRequest} because PHARMACY_VERIFIER may raise a question but is
   * not on {@code GET /prescriptions/{id}}.
   */
  protected readonly canReadExchange = computed(() =>
    this.roleContext.hasAnyActiveRole(PrescriptionClarificationComponent.READ_ROLES),
  );

  /**
   * The control renders at all only when there is something to do with it.
   * A row the prescriber has just answered is back in the status it was
   * asked from, so {@link canRequest} already covers the read case.
   */
  protected readonly visible = computed(() => this.canRequest() || this.canResolve());

  protected readonly buttonKey = computed(() => {
    if (!this.isPharmacy()) return 'PRESCRIPTIONS.CLARIFICATION.RESOLVE';
    return this.hasAnswer()
      ? 'PRESCRIPTIONS.CLARIFICATION.READ_ANSWER'
      : 'PRESCRIPTIONS.CLARIFICATION.ASK';
  });

  /** Per-instance so `aria-labelledby` is unique across a page of rows. */
  protected readonly titleId = computed(() => `rx-clarify-title-${this.prescriptionId()}`);

  /** There is an exchange on screen, or a reason there is not. */
  protected readonly showExchange = computed(
    () =>
      this.isPharmacy() &&
      (this.loadingExchange() ||
        this.exchangeError() ||
        !!this.fetchedQuestion() ||
        !!this.fetchedAnswer()),
  );

  protected openDialog(): void {
    this.text = '';
    this.submitError.set(null);
    this.open.set(true);
    // Always, not only when the row is flagged CLARIFICATION_RESOLVED. The
    // backend resolves ONE attentionReason by precedence (status first, then
    // an outstanding back order, then the clarification), and
    // resolveClarification restores the PREVIOUS status — so a question
    // raised on a PENDING_STOCK or PARTNER_REJECTED order comes back
    // flagged with that status and the answer would be unreachable from the
    // queue. Fetching on open costs one request and removes the dead end.
    if (this.isPharmacy()) {
      this.loadExchange();
    }
  }

  /**
   * Closing is refused while a post is in flight: the refusal lands on
   * `submitError` inside this dialog, and dismissing it mid-flight would
   * discard both the message and the text the user typed.
   */
  protected requestClose(): void {
    if (this.submitting()) return;
    this.close();
  }

  protected close(): void {
    this.open.set(false);
    this.text = '';
    this.submitError.set(null);
    this.exchangeError.set(false);
    // Focus goes back where it came from, not to the top of the document.
    this.trigger()?.nativeElement.focus();
  }

  /**
   * The work-queue projection carries the attention flag but not the words,
   * so the exchange is fetched from the prescription record. Only attempted
   * for a role the endpoint admits: rendering a 403 as an error the user can
   * act on would be a lie, and firing it at all would be noise.
   */
  private loadExchange(): void {
    if (!this.canReadExchange()) return;
    this.exchangeRequests.next();
  }

  protected retryExchange(): void {
    this.loadExchange();
  }

  protected submit(): void {
    if (this.submitting()) return;
    const id = this.prescriptionId();
    this.submitting.set(true);
    this.submitError.set(null);
    const call = this.isPharmacy()
      ? this.prescriptions.requestClarification(id, this.text)
      : this.prescriptions.resolveClarification(id, this.text);
    const successKey = this.isPharmacy()
      ? 'PRESCRIPTIONS.CLARIFICATION.SENT'
      : 'PRESCRIPTIONS.CLARIFICATION.RESOLVED';
    const failureKey = this.isPharmacy()
      ? 'PRESCRIPTIONS.CLARIFICATION.SEND_FAILED'
      : 'PRESCRIPTIONS.CLARIFICATION.RESOLVE_FAILED';
    call.subscribe({
      next: () => {
        this.submitting.set(false);
        this.toast.success(this.translate.instant(successKey));
        this.close();
        this.changed.emit();
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.submitError.set(this.refusalMessage(err, failureKey));
      },
    });
  }

  /**
   * A workflow refusal carries a message worth reading ("this one is
   * PENDING_CLARIFICATION"), so it is shown verbatim. A 403 does not:
   * {@code GlobalExceptionHandler} answers every {@code AccessDeniedException}
   * with the literal "Access denied", which tells a doctor credentialed at
   * another hospital nothing about why or what to do, so the localized rule
   * replaces it.
   */
  private refusalMessage(err: unknown, failureKey: string): string {
    const status = (err as { status?: number } | null)?.status;
    if (status === 403) {
      return this.translate.instant(
        this.isPharmacy()
          ? 'PRESCRIPTIONS.CLARIFICATION.FORBIDDEN_REQUEST'
          : 'PRESCRIPTIONS.CLARIFICATION.FORBIDDEN_RESOLVE',
      );
    }
    return this.extractMessage(err) || this.translate.instant(failureKey);
  }

  /** A question is mandatory; an answer is not (the order may have been edited). */
  protected requiredTextMissing(): boolean {
    return this.isPharmacy() && this.text.trim().length === 0;
  }

  private extractMessage(err: unknown): string {
    const body = (err as { error?: { message?: string } } | null)?.error;
    return typeof body?.message === 'string' ? body.message : '';
  }
}
