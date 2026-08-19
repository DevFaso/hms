/**
 * Environment-driven configuration for the KC-4 user migration.
 *
 * All secrets (DB URL, Keycloak admin credentials) come from environment
 * variables — never committed. Missing required values cause an immediate
 * fatal exit so dry-runs cannot accidentally point at the wrong realm.
 */

export interface MigrationConfig {
  readonly databaseUrl: string;
  readonly keycloak: {
    readonly baseUrl: string;
    readonly realm: string;
    readonly adminClientId: string;
    readonly adminUsername: string;
    readonly adminPassword: string;
  };
  readonly batchSize: number;
  readonly dryRun: boolean;
  readonly forcePasswordReset: boolean;
  readonly requireEmailVerified: boolean;
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

function required(name: string, env: NodeJS.ProcessEnv = process.env): string {
  const raw = env[name];
  if (raw === undefined || raw.trim().length === 0) {
    throw new ConfigError(`Missing required env var: ${name}`);
  }
  return raw.trim();
}

function optional(name: string, fallback: string, env: NodeJS.ProcessEnv = process.env): string {
  const raw = env[name];
  if (raw === undefined || raw.trim().length === 0) {
    return fallback;
  }
  return raw.trim();
}

function parseBool(raw: string, fallback: boolean): boolean {
  const normalized = raw.trim().toLowerCase();
  if (['true', '1', 'yes', 'on'].includes(normalized)) return true;
  if (['false', '0', 'no', 'off'].includes(normalized)) return false;
  return fallback;
}

function parseInteger(raw: string, fallback: number, min = 1, max = 10_000): number {
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed) || parsed < min || parsed > max) return fallback;
  return parsed;
}

export interface LoadConfigOptions {
  readonly argv?: readonly string[];
  readonly env?: NodeJS.ProcessEnv;
}

export function loadConfig(options: LoadConfigOptions = {}): MigrationConfig {
  const env = options.env ?? process.env;
  const argv = options.argv ?? process.argv.slice(2);
  const cliDryRun = argv.includes('--dry-run');

  return {
    databaseUrl: required('HMS_DATABASE_URL', env),
    keycloak: {
      baseUrl: required('KEYCLOAK_BASE_URL', env).replace(/\/+$/, ''),
      realm: optional('KEYCLOAK_REALM', 'hms', env),
      adminClientId: optional('KEYCLOAK_ADMIN_CLIENT_ID', 'admin-cli', env),
      adminUsername: required('KEYCLOAK_ADMIN_USERNAME', env),
      adminPassword: required('KEYCLOAK_ADMIN_PASSWORD', env),
    },
    batchSize: parseInteger(optional('MIGRATION_BATCH_SIZE', '50', env), 50),
    dryRun: cliDryRun || parseBool(optional('MIGRATION_DRY_RUN', 'false', env), false),
    forcePasswordReset: parseBool(optional('MIGRATION_FORCE_PASSWORD_RESET', 'true', env), true),
    requireEmailVerified: parseBool(optional('MIGRATION_REQUIRE_EMAIL_VERIFIED', 'false', env), false),
  };
}
