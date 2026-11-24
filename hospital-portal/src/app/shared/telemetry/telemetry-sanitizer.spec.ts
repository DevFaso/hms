import { sanitizeTelemetryItem, sanitizeText, sanitizeUrl } from './telemetry-sanitizer';

const PATIENT = '3f2c1a9e-8b7d-4c6e-9f10-a1b2c3d4e5f6';

describe('telemetry sanitizer', () => {
  describe('sanitizeUrl', () => {
    it('drops the query string and the fragment', () => {
      expect(sanitizeUrl('https://app.example.org/patients?search=Awa&dob=1991-07-22#top')).toBe(
        'https://app.example.org/patients',
      );
      expect(sanitizeUrl('/verify?email=a%40b.c&token=t1')).toBe('/verify');
    });

    it('replaces UUIDs, e-mails and anything carrying a digit in the path', () => {
      expect(sanitizeUrl(`/api/patients/${PATIENT}/vitals`)).toBe('/api/patients/:uuid/vitals');
      expect(sanitizeUrl('/api/lookup/appointment/patient/email/a%40b.c')).toBe(
        '/api/lookup/appointment/patient/email/:email',
      );
      expect(sanitizeUrl('/api/lookup/appointment/patient/phone/+22670161099')).toBe(
        '/api/lookup/appointment/patient/phone/:id',
      );
      expect(sanitizeUrl('/api/lab-orders/42/results')).toBe('/api/lab-orders/:id/results');
    });

    it('keeps the route words, API versions and static asset names', () => {
      expect(sanitizeUrl('https://app.example.org/fhir/R4/Patient')).toBe(
        'https://app.example.org/fhir/R4/Patient',
      );
      expect(sanitizeUrl('/main-5XQ2ABCD.js')).toBe('/main-5XQ2ABCD.js');
    });
  });

  describe('sanitizeText', () => {
    it('scrubs the URLs, UUIDs and e-mail addresses inside free text', () => {
      expect(
        sanitizeText(
          `Http failure response for https://app.example.org/api/patients/search?name=Awa: 400 (${PATIENT}, awa@example.org)`,
        ),
      ).toBe(
        'Http failure response for https://app.example.org/api/patients/search: 400 (:uuid, :email)',
      );
      expect(sanitizeText('GET /api/users/search?email=x@y.z failed')).toBe(
        'GET /api/users/search failed',
      );
    });

    it('leaves text without identifiers alone', () => {
      expect(sanitizeText('faro.tracing.fetch')).toBe('faro.tracing.fetch');
      expect(sanitizeText('2026-11-23T10:00:00Z')).toBe('2026-11-23T10:00:00Z');
    });
  });

  describe('sanitizeTelemetryItem (Faro beforeSend)', () => {
    it('scrubs span URLs and drops header and body attributes from a trace', () => {
      const item = {
        type: 'trace',
        meta: { page: { url: `https://app.example.org/patients/${PATIENT}?tab=chart` } },
        payload: {
          resourceSpans: [
            {
              scopeSpans: [
                {
                  spans: [
                    {
                      traceId: '0af7651916cd43dd8448eb211c80319c',
                      attributes: [
                        { key: 'http.method', value: { stringValue: 'POST' } },
                        {
                          key: 'http.url',
                          value: {
                            stringValue: 'https://app.example.org/api/patients/search?name=Awa',
                          },
                        },
                        {
                          key: 'http.target',
                          value: { stringValue: '/api/patients/lookup?phone=70161099' },
                        },
                        {
                          key: 'http.request.header.authorization',
                          value: { stringValue: 'Bearer x' },
                        },
                        { key: 'http.request.body', value: { stringValue: '{"name":"Awa"}' } },
                        { key: 'http.status_code', value: { intValue: 200 } },
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        },
      };

      const out = sanitizeTelemetryItem(item);
      const attributes = out.payload.resourceSpans[0].scopeSpans[0].spans[0].attributes;

      expect(out.meta.page.url).toBe('https://app.example.org/patients/:uuid');
      expect(attributes.map((a) => a.key)).toEqual([
        'http.method',
        'http.url',
        'http.target',
        'http.status_code',
      ]);
      expect(attributes[1].value).toEqual({
        stringValue: 'https://app.example.org/api/patients/search',
      });
      expect(attributes[2].value).toEqual({ stringValue: '/api/patients/lookup' });
      expect(out.payload.resourceSpans[0].scopeSpans[0].spans[0].traceId).toBe(
        '0af7651916cd43dd8448eb211c80319c',
      );
      // The input is not mutated.
      expect(item.meta.page.url).toContain('?tab=chart');
    });

    it('scrubs events, exceptions and logs, and drops header and body fields', () => {
      const out = sanitizeTelemetryItem({
        type: 'event',
        payload: {
          name: 'faro.performance.resource',
          attributes: {
            name: '/api/reception/patients/duplicate-candidates?name=Awa',
            decodedBodySize: '512',
          },
          headers: { cookie: 'XSRF-TOKEN=1' },
          body: '{"phone":"70161099"}',
        },
        meta: {},
      });

      expect(out.payload as unknown).toEqual({
        name: 'faro.performance.resource',
        attributes: {
          name: '/api/reception/patients/duplicate-candidates',
          decodedBodySize: '512',
        },
      });
    });
  });
});
