// Guards the explicit `name` field on every environment file. The Faro RUM
// `app.environment` tag is wired to this in main.ts; if a future edit drops or
// mistypes the field, telemetry from UAT/dev silently lands in the prod
// dashboard (or, worse, ships to the wrong collector and is blocked by CORS).
import { environment as local } from './environment';
import { environment as dev } from './environment.dev';
import { environment as uat } from './environment.uat';
import { environment as prod } from './environment.prod';

describe('environment.name', () => {
  it('local environment is tagged "local"', () => {
    expect(local.name).toBe('local');
  });

  it('dev environment is tagged "dev"', () => {
    expect(dev.name).toBe('dev');
  });

  it('uat environment is tagged "uat" and Faro is disabled', () => {
    expect(uat.name).toBe('uat');
    // UAT must not ship telemetry to the prod Faro app — the prod app does not
    // accept https://hms.uat.bitnesttechs.com as an origin, so enabling here
    // just produces CORS noise. Re-enable only after a UAT-specific Faro app
    // is provisioned and its allowed-origin list updated.
    expect(uat.faroCollectorUrl).toBe('');
  });

  it('prod environment is tagged "production" and ships only when production=true', () => {
    expect(prod.name).toBe('production');
    expect(prod.production).toBe(true);
  });
});
