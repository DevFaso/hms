/**
 * Minimal Keycloak admin REST client used by the KC-4 migration.
 *
 * Why not `@keycloak/keycloak-admin-client`?
 *   - The official client pulls 50+ runtime deps and is Node-CJS-first,
 *     which complicates ESM tsx scripts.
 *   - We only need five endpoints: token, list users, create user,
 *     assign realm roles, send execute-actions-email.
 *
 * Every method rejects with a `KeycloakError` that carries the HTTP status
 * so the migration loop can distinguish "conflict → idempotent skip" from
 * "fatal → abort batch".
 */

export interface KeycloakClientOptions {
  readonly baseUrl: string;
  readonly realm: string;
  readonly adminClientId: string;
  readonly adminUsername: string;
  readonly adminPassword: string;
  readonly fetchImpl?: typeof fetch;
  readonly now?: () => number;
}

export interface KeycloakUserPayload {
  readonly username: string;
  readonly email: string;
  readonly firstName: string | null;
  readonly lastName: string | null;
  readonly enabled: boolean;
  readonly emailVerified: boolean;
  readonly attributes: Readonly<Record<string, readonly string[]>>;
  readonly requiredActions: readonly string[];
}

export interface KeycloakRoleRef {
  readonly id: string;
  readonly name: string;
}

export class KeycloakError extends Error {
  readonly status: number;
  readonly body: string;

  constructor(message: string, status: number, body: string) {
    super(message);
    this.name = 'KeycloakError';
    this.status = status;
    this.body = body;
  }
}

interface TokenResponse {
  readonly access_token: string;
  readonly expires_in: number;
}

export class KeycloakAdminClient {
  private readonly opts: Required<Omit<KeycloakClientOptions, 'fetchImpl' | 'now'>> & {
    readonly fetchImpl: typeof fetch;
    readonly now: () => number;
  };

  private token: string | null = null;
  private tokenExpiresAtMs = 0;

  constructor(options: KeycloakClientOptions) {
    this.opts = {
      baseUrl: options.baseUrl.replace(/\/+$/, ''),
      realm: options.realm,
      adminClientId: options.adminClientId,
      adminUsername: options.adminUsername,
      adminPassword: options.adminPassword,
      fetchImpl: options.fetchImpl ?? fetch,
      now: options.now ?? Date.now,
    };
  }

  /** Acquire an admin token from the master realm. Cached with a 30 s safety window. */
  private async getAccessToken(): Promise<string> {
    if (this.token && this.opts.now() < this.tokenExpiresAtMs - 30_000) {
      return this.token;
    }

    const body = new URLSearchParams({
      grant_type: 'password',
      client_id: this.opts.adminClientId,
      username: this.opts.adminUsername,
      password: this.opts.adminPassword,
    });

    const res = await this.opts.fetchImpl(
      `${this.opts.baseUrl}/realms/master/protocol/openid-connect/token`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body.toString(),
      },
    );

    if (!res.ok) {
      throw new KeycloakError(
        `Admin token request failed: HTTP ${res.status}`,
        res.status,
        await safeText(res),
      );
    }

    const json = (await res.json()) as TokenResponse;
    this.token = json.access_token;
    this.tokenExpiresAtMs = this.opts.now() + json.expires_in * 1000;
    return this.token;
  }

  private async authedFetch(path: string, init: RequestInit = {}): Promise<Response> {
    const token = await this.getAccessToken();
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${token}`);
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    return this.opts.fetchImpl(`${this.opts.baseUrl}${path}`, { ...init, headers });
  }

  /** Returns the existing user's id when a matching username already exists, else null. */
  async findUserIdByUsername(username: string): Promise<string | null> {
    const params = new URLSearchParams({ username, exact: 'true' });
    const res = await this.authedFetch(
      `/admin/realms/${encodeURIComponent(this.opts.realm)}/users?${params.toString()}`,
    );
    if (!res.ok) {
      throw new KeycloakError(
        `Lookup user ${username} failed: HTTP ${res.status}`,
        res.status,
        await safeText(res),
      );
    }
    const list = (await res.json()) as Array<{ id: string; username: string }>;
    const match = list.find((u) => u.username === username);
    return match?.id ?? null;
  }

  /** Returns the new user id. Throws on any non-2xx including 409. */
  async createUser(payload: KeycloakUserPayload): Promise<string> {
    const res = await this.authedFetch(
      `/admin/realms/${encodeURIComponent(this.opts.realm)}/users`,
      { method: 'POST', body: JSON.stringify(payload) },
    );
    if (res.status === 201) {
      const location = res.headers.get('location');
      const id = location?.split('/').pop();
      if (!id) {
        throw new KeycloakError('Create user: missing Location header', res.status, '');
      }
      return id;
    }
    throw new KeycloakError(
      `Create user ${payload.username} failed: HTTP ${res.status}`,
      res.status,
      await safeText(res),
    );
  }

  /** Resolves realm-role names to `{id,name}` refs in a single call. Missing roles are omitted. */
  async resolveRealmRoles(roleNames: readonly string[]): Promise<KeycloakRoleRef[]> {
    if (roleNames.length === 0) return [];
    const resolved: KeycloakRoleRef[] = [];
    for (const name of roleNames) {
      const res = await this.authedFetch(
        `/admin/realms/${encodeURIComponent(this.opts.realm)}/roles/${encodeURIComponent(name)}`,
      );
      if (res.status === 404) continue;
      if (!res.ok) {
        throw new KeycloakError(
          `Resolve role ${name} failed: HTTP ${res.status}`,
          res.status,
          await safeText(res),
        );
      }
      const role = (await res.json()) as { id: string; name: string };
      resolved.push({ id: role.id, name: role.name });
    }
    return resolved;
  }

  async assignRealmRoles(userId: string, roles: readonly KeycloakRoleRef[]): Promise<void> {
    if (roles.length === 0) return;
    const res = await this.authedFetch(
      `/admin/realms/${encodeURIComponent(this.opts.realm)}/users/${encodeURIComponent(
        userId,
      )}/role-mappings/realm`,
      { method: 'POST', body: JSON.stringify(roles) },
    );
    if (res.status !== 204 && !res.ok) {
      throw new KeycloakError(
        `Assign realm roles to ${userId} failed: HTTP ${res.status}`,
        res.status,
        await safeText(res),
      );
    }
  }

  /** Triggers Keycloak to email the user the UPDATE_PASSWORD + VERIFY_EMAIL actions. */
  async sendExecuteActionsEmail(userId: string, actions: readonly string[]): Promise<void> {
    if (actions.length === 0) return;
    const res = await this.authedFetch(
      `/admin/realms/${encodeURIComponent(this.opts.realm)}/users/${encodeURIComponent(
        userId,
      )}/execute-actions-email`,
      { method: 'PUT', body: JSON.stringify(actions) },
    );
    if (res.status !== 204 && !res.ok) {
      throw new KeycloakError(
        `Execute-actions-email for ${userId} failed: HTTP ${res.status}`,
        res.status,
        await safeText(res),
      );
    }
  }
}

async function safeText(res: Response): Promise<string> {
  try {
    return await res.text();
  } catch {
    return '';
  }
}
