// Guards the explicit `name` field on every environment file. The Faro RUM
// `app.environment` tag is wired to this in main.ts; if a future edit drops or
// mistypes the field, telemetry from dev silently lands in the prod
// dashboard (or, worse, ships to the wrong collector and is blocked by CORS).
import { environment as local } from './environment';
import { environment as dev } from './environment.dev';
import { environment as prod } from './environment.prod';

describe('environment.name', () => {
  it('local environment is tagged "local"', () => {
    expect(local.name).toBe('local');
  });

  it('dev environment is tagged "dev"', () => {
    expect(dev.name).toBe('dev');
  });

  it('dev environment does not ship telemetry to the prod Faro app', () => {
    // The prod Faro app does not accept https://dev.e-keneya.com as an
    // origin, so enabling here just produces CORS noise. Re-enable only
    // after a dev-specific Faro app is provisioned and its allowed-origin
    // list updated.
    expect(dev.faroCollectorUrl).toBe('');
  });

  it('prod environment is tagged "production" and ships only when production=true', () => {
    expect(prod.name).toBe('production');
    expect(prod.production).toBe(true);
  });
});
