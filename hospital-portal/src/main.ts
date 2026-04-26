import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { environment } from './environments/environment';

// Initialize Grafana Faro for real user monitoring (RUM) before Angular boots.
// Only active when faroCollectorUrl is configured (uat/prod).
if (environment.faroCollectorUrl) {
  import('@grafana/faro-web-sdk').then(({ getWebInstrumentations, initializeFaro }) =>
    import('@grafana/faro-web-tracing').then(({ TracingInstrumentation }) => {
      initializeFaro({
        url: environment.faroCollectorUrl,
        app: {
          name: 'HMS',
          version: '1.0.0',
          environment: environment.name,
        },
        instrumentations: [...getWebInstrumentations(), new TracingInstrumentation()],
      });
    }),
  );
}

bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
