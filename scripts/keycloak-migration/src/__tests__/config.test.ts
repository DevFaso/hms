import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { ConfigError, loadConfig } from '../config.ts';

const baseEnv = {
  HMS_DATABASE_URL: 'postgres://user:pw@localhost:5432/hms',
  KEYCLOAK_BASE_URL: 'https://kc.example.com/',
  KEYCLOAK_ADMIN_USERNAME: 'admin',
  KEYCLOAK_ADMIN_PASSWORD: 'secret',
} as const;

describe('loadConfig', () => {
  it('returns defaults when only required vars are set', () => {
    const config = loadConfig({ argv: [], env: { ...baseEnv } });
    assert.equal(config.keycloak.realm, 'hms');
    assert.equal(config.keycloak.adminClientId, 'admin-cli');
    assert.equal(config.keycloak.baseUrl, 'https://kc.example.com');
    assert.equal(config.batchSize, 50);
    assert.equal(config.dryRun, false);
    assert.equal(config.forcePasswordReset, true);
    assert.equal(config.requireEmailVerified, false);
  });

  it('enables dry-run via CLI flag', () => {
    const config = loadConfig({ argv: ['--dry-run'], env: { ...baseEnv } });
    assert.equal(config.dryRun, true);
  });

  it('enables dry-run via env var', () => {
    const config = loadConfig({
      argv: [],
      env: { ...baseEnv, MIGRATION_DRY_RUN: 'yes' },
    });
    assert.equal(config.dryRun, true);
  });

  it('rejects missing required vars with ConfigError', () => {
    const { HMS_DATABASE_URL: _ignored, ...partial } = baseEnv;
    assert.throws(
      () => loadConfig({ argv: [], env: partial as NodeJS.ProcessEnv }),
      ConfigError,
    );
  });

  it('treats blank-string required var as missing', () => {
    assert.throws(
      () => loadConfig({ argv: [], env: { ...baseEnv, KEYCLOAK_ADMIN_USERNAME: '   ' } }),
      ConfigError,
    );
  });

  it('falls back to default batch size on garbage input', () => {
    const config = loadConfig({
      argv: [],
      env: { ...baseEnv, MIGRATION_BATCH_SIZE: 'not-a-number' },
    });
    assert.equal(config.batchSize, 50);
  });
});
