import { HttpResponse } from '@angular/common/http';

/**
 * QA D1009: invoice PDFs were saved as "invoice-<uuid>.pdf" and every receipt
 * of an invoice under the same name, so the second one overwrote the first.
 * The server already names each document (invoice number, receipt number);
 * the browser keeps that name, and falls back to one the caller builds from a
 * readable reference — never from a database id.
 */

/** The file name in a Content-Disposition header, RFC 5987 form first; null when there is none. */
export function filenameFromDisposition(header: string | null | undefined): string | null {
  if (!header) return null;
  const extended = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header);
  if (extended) {
    try {
      return cleanFilename(decodeURIComponent(extended[1].trim()));
    } catch {
      // A malformed escape falls through to the plain form.
    }
  }
  const plain = /filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;]+)/.exec(header);
  const name = plain ? (plain[1] ?? plain[2]) : null;
  return name ? cleanFilename(name.trim()) : null;
}

/** A file name safe on every OS: no path, no reserved characters. */
export function cleanFilename(name: string): string {
  const base = name.split(/[\\/]/).pop() ?? name;
  return base.replace(/[<>:"|?*]+/g, '_').trim();
}

/** Saves a blob under the given name through a temporary link. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = cleanFilename(filename);
  a.click();
  URL.revokeObjectURL(url);
}

/** Saves a downloaded document under the server's name, else the readable fallback. */
export function saveResponse(response: HttpResponse<Blob>, fallback: string): void {
  const name = filenameFromDisposition(response.headers.get('Content-Disposition')) || fallback;
  saveBlob(response.body ?? new Blob(), name);
}
