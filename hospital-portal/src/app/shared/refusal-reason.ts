import { TranslateService } from '@ngx-translate/core';
import { serverMessage } from '../services/provider.model';

/**
 * QA D732/D746: the reason a 4xx refusal carries (the backend localises it,
 * and a validation refusal's field reason wins over the generic text), or
 * `null` for anything else, so a 5xx body never reaches the user and the
 * caller falls back to its own translated message.
 */
export function refusalReason(err: unknown): string | null {
  const status = (err as { status?: unknown } | null)?.status;
  return typeof status === 'number' && status >= 400 && status < 500 ? serverMessage(err) : null;
}

/**
 * QA D808: a bean-validation refusal's field reason is the bare constraint
 * text ("doit être inférieur ou égal à 10"), which does not say which field it
 * is about. With the form's field → label-key map, each such reason is shown
 * after the field's label. A business refusal already names what it is about,
 * so it is shown as sent. Anything that is not a 4xx gives `null`, as
 * {@link refusalReason} does.
 */
export function labelledRefusalReason(
  err: unknown,
  translate: TranslateService,
  labelKeys: Readonly<Record<string, string>>,
): string | null {
  const reason = refusalReason(err);
  if (reason === null) return null;
  const body = (err as { error?: unknown }).error as
    { error?: unknown; fieldErrors?: unknown } | null | undefined;
  const fieldErrors = body?.fieldErrors;
  if (body?.error !== 'Validation Failed' || !fieldErrors || typeof fieldErrors !== 'object') {
    return reason;
  }
  const labelled = Object.entries(fieldErrors as Record<string, unknown>)
    .filter(
      (entry): entry is [string, string] => typeof entry[1] === 'string' && entry[1].trim() !== '',
    )
    .map(([field, text]) =>
      labelKeys[field]
        ? translate.instant('FIELD_LIMITS.FIELD_REASON', {
            field: translate.instant(labelKeys[field]),
            reason: text,
          })
        : text,
    );
  return labelled.length > 0 ? [...new Set(labelled)].join('. ') : reason;
}
