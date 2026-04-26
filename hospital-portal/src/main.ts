import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { environment } from './environments/environment';

/**
 * Detects an environment/host mismatch and returns the effective env name.
 *
 * The Railway frontend services build with `--configuration=$BUILD_CONFIG`. If that
 * variable is missing on a per-env service, the image silently ships
 * `environment.prod.ts` — which points Faro at the prod RUM collector and tags every
 * event as `production`. The prod Faro app's allowed-origin list does not include
 * `*.uat.bitnesttechs.com`, so the browser then sees a CORS preflight failure on
 * every event, which surfaces as red noise in DevTools.
 *
 * Rather than ship telemetry to the wrong app and pollute prod dashboards with UAT
 * traffic, we refuse to initialise Faro when the configured environment doesn't match
 * the hostname we're served from.
 */
function resolveFaroEnvironment(): { url: string; envName: string } | null {
  const configuredUrl = environment.faroCollectorUrl;
  if (!configuredUrl) return null;

  const host = typeof globalThis !== 'undefined' ? (globalThis.location?.hostname ?? '') : '';
  const inferred = inferEnvFromHost(host);

  if (inferred && inferred !== environment.name) {
    // Build/host mismatch — the bundle was built for `environment.name` but is being
    // served from a host that looks like `inferred`. Skip Faro entirely; logging it to
    // the console gives operators a quick signal without breaking the page.
    console.warn(
      `[faro] Disabled: built for "${environment.name}" but served from "${host}" (looks like "${inferred}"). ` +
        `Set BUILD_CONFIG=${inferred} on the Railway frontend service for this environment.`,
    );
    return null;
  }

  return { url: configuredUrl, envName: environment.name };
}

function inferEnvFromHost(host: string): 'uat' | 'dev' | 'production' | null {
  if (!host) return null;
  if (host.includes('.uat.') || host.startsWith('uat.')) return 'uat';
  if (host.includes('.dev.') || host.startsWith('dev.')) return 'dev';
  if (host === 'localhost' || host === '127.0.0.1') return null; // local builds are always fine
  return 'production';
}

const faro = resolveFaroEnvironment();
if (faro) {
  // Initialize Grafana Faro for real user monitoring (RUM) before Angular boots.
  import('@grafana/faro-web-sdk').then(({ getWebInstrumentations, initializeFaro }) =>
    import('@grafana/faro-web-tracing').then(({ TracingInstrumentation }) => {
      initializeFaro({
        url: faro.url,
        app: {
          name: 'HMS',
          version: '1.0.0',
          environment: faro.envName,
        },
        instrumentations: [...getWebInstrumentations(), new TracingInstrumentation()],
      });
    }),
  );
}

bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
