import { HttpErrorResponse } from '@angular/common/http';
import { blobRefusalReason } from './blob-refusal';

describe('blobRefusalReason (QA D1184)', () => {
  const blob = (body: string) => new Blob([body], { type: 'application/json' });

  it('reads the localized message of a 404 sent to a blob request', async () => {
    const err = new HttpErrorResponse({
      status: 404,
      error: blob(JSON.stringify({ message: "Le fichier stocké n'est plus disponible." })),
    });
    expect(await blobRefusalReason(err)).toBe("Le fichier stocké n'est plus disponible.");
  });

  it('reads an already-parsed body too', async () => {
    const err = new HttpErrorResponse({ status: 409, error: { message: 'Déjà activé.' } });
    expect(await blobRefusalReason(err)).toBe('Déjà activé.');
  });

  it('gives null for a 5xx, a non-JSON body, an empty message or a non-HTTP error', async () => {
    expect(
      await blobRefusalReason(
        new HttpErrorResponse({ status: 500, error: blob('{"message":"x"}') }),
      ),
    ).toBeNull();
    expect(
      await blobRefusalReason(new HttpErrorResponse({ status: 404, error: blob('<html>') })),
    ).toBeNull();
    expect(
      await blobRefusalReason(
        new HttpErrorResponse({ status: 404, error: blob('{"message":"  "}') }),
      ),
    ).toBeNull();
    expect(await blobRefusalReason(new Error('boom'))).toBeNull();
  });
});
