// Railway Infrastructure as Code for the "e-keneya" project.
//
// Replaces the three Config-as-Code files (railway.toml, hospital-portal/railway.toml,
// keycloak/prod/railway.toml), which Railway stops reading on 2026-12-01.
//
// READ THIS BEFORE EDITING
// - Railway does NOT read this file during a deploy. Nothing changes until someone runs
//   `railway config apply` against an environment. Procedure, verification and rollback:
//   docs/runbooks/railway-config-as-code.md
// - The file describes a WHOLE environment. A resource that exists on Railway but is
//   missing from the list returned for that environment is DELETED by the next apply:
//   databases, volumes and buckets included. Never remove a line to "tidy up".
// - dev and prod hold different services, so the file returns a different list per
//   environment and refuses any environment it does not know.
// - No variable is declared here (`variables: { managed: false }`): every variable and
//   secret stays in the Railway dashboard, and apply never reads, writes or deletes one.

import { bucket, database, defineRailway, github, project, service, volume } from "railway/iac";

const REPO = "DevFaso/hms";
const REGION = "us-east4-eqdc4a";
const ONE_REPLICA = { [REGION]: 1 };

// ---- Settings moved from the three railway.toml files (identical in dev and prod) ----

// Was railway.toml (repo root). The Dockerfile path is relative to the repo root.
// Watch patterns: only rebuild the backend when something that reaches the bootJar or
// its runtime image changes, not on every frontend, Keycloak, docs or CI push.
const BACKEND_BUILD = {
  buildEnvironment: "V3",
  builder: "DOCKERFILE",
  dockerfilePath: "Dockerfile",
  watchPatterns: [
    "hospital-core/**",
    "Dockerfile",
    ".dockerignore",
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
    "gradle/**",
    "gradlew",
    "railway.toml",
  ],
} as const;
const BACKEND_HEALTHCHECK = { healthcheck: "/api/actuator/health", healthcheckTimeout: 180 };

// Was hospital-portal/railway.toml. The Dockerfile path is relative to the service's
// root directory (/hospital-portal); the watch pattern is relative to the repo root.
// Each frontend service must keep its BUILD_CONFIG variable (dev / production) in the
// dashboard: it picks the Angular environment at build time.
const FRONTEND_BUILD = {
  buildEnvironment: "V3",
  builder: "DOCKERFILE",
  dockerfilePath: "Dockerfile",
  watchPatterns: ["hospital-portal/**"],
} as const;
const FRONTEND_HEALTHCHECK = { healthcheck: "/", healthcheckTimeout: 60 };

// Was keycloak/prod/railway.toml, shared by every hms-keycloak-* service.
// Health check: Keycloak 26 serves /health/ready on the management port (9000), which
// Railway's edge cannot reach; /realms/master/ is on the main port and returns 200
// whenever Keycloak is up.
const KEYCLOAK_BUILD = {
  buildEnvironment: "V3",
  builder: "DOCKERFILE",
  dockerfilePath: "keycloak/prod/Dockerfile",
  watchPatterns: ["keycloak/**", ".dockerignore"],
} as const;
const KEYCLOAK_HEALTHCHECK = { healthcheck: "/realms/master/", healthcheckTimeout: 300 };

// Backend and frontend: restart on failure, give up after 3 attempts.
const RESTART_ON_FAILURE_3 = { restartPolicyType: "ON_FAILURE", restartPolicyMaxRetries: 3 } as const;
// Keycloak: always restart. With on-failure + 3 retries a Keycloak that crashed more
// than three times stayed down and every sign-in failed (D16, keycloak/prod/README.md).
const RESTART_ALWAYS = { restartPolicyType: "ALWAYS" } as const;

// Database images are pinned to what each database runs today instead of relying on
// the postgres() / redis() helpers, whose default image moves with the SDK version.
// The application databases run PostgreSQL 17 and the Keycloak databases PostgreSQL 18.
// Pointing a database at another major version stops it from starting on its existing
// data directory: a major upgrade is a planned dump-and-restore, never an edit here.
const POSTGRES_17_IMAGE = "ghcr.io/railwayapp-templates/postgres-ssl:17";
const POSTGRES_18_IMAGE = "ghcr.io/railwayapp-templates/postgres-ssl:18";
const REDIS_IMAGE = "redis:8.2";

function postgresDb(name: string, image: string) {
  return database(name, "postgres", {
    image,
    output: "DATABASE_URL",
    defaultMountPath: "/var/lib/postgresql/data",
    region: REGION,
  });
}

function redisDb(name: string) {
  return database(name, "redis", {
    image: REDIS_IMAGE,
    output: "REDIS_URL",
    defaultMountPath: "/data",
    region: REGION,
  });
}

const REDIS_START_COMMAND =
  '/bin/sh -c "rm -rf $RAILWAY_VOLUME_MOUNT_PATH/lost+found/ && exec docker-entrypoint.sh redis-server --requirepass $REDIS_PASSWORD --save 60 1 --dir $RAILWAY_VOLUME_MOUNT_PATH"';
const VOLUME_50_GB = {
  alerts: { usage: { "100": {}, "80": {}, "95": {} } },
  allowOnlineResize: true,
  region: REGION,
  sizeMB: 50000,
};

// ---- dev environment (deploys the develop branch) ----

function devResources() {
  const keycloakDb = postgresDb("hms-keycloak-dev-db", POSTGRES_18_IMAGE);
  keycloakDb.deploy = { multiRegionConfig: { [REGION]: { numReplicas: 1 } } };
  keycloakDb.networking = { tcpProxies: { "5432": {} } };

  const appDb = postgresDb("hms-Postgres-dev", POSTGRES_17_IMAGE);
  appDb.deploy = { multiRegionConfig: { [REGION]: { numReplicas: 1 } } };
  appDb.networking = { privateNetworkEndpoint: "postgres-aqxj", tcpProxies: { "5432": {} } };

  const cache = redisDb("Redis-nABk");
  cache.deploy = { multiRegionConfig: { [REGION]: { numReplicas: 1 } }, startCommand: REDIS_START_COMMAND };
  cache.networking = { privateNetworkEndpoint: "redis-nabk" };

  // Volumes. redis-volume-GW5t is currently attached to nothing; it is listed so that
  // apply does not delete it.
  const backendUploads = volume("hms-backend-dev-volume-7sW6", VOLUME_50_GB);
  const keycloakDbVolume = volume("postgres-volume-RAIL", VOLUME_50_GB);
  const detachedRedisVolume = volume("redis-volume-GW5t", VOLUME_50_GB);
  const appDbVolume = volume("hms-postgres-dev-volume-z_rr", VOLUME_50_GB);
  const cacheVolume = volume("redis-volume-7j0a", VOLUME_50_GB);

  const backend = service("hms-backend-dev", {
    source: github(REPO, { branch: "develop", checkSuites: false }),
    build: BACKEND_BUILD,
    ...BACKEND_HEALTHCHECK,
    replicas: ONE_REPLICA,
    deploy: { limitOverride: { containers: { cpu: 32, memoryBytes: 6000000000 } }, ...RESTART_ON_FAILURE_3 },
    domains: ["api.dev.e-keneya.com"],
    networking: { privateNetworkEndpoint: "patient-stillness" },
    volumeMounts: { "/data/uploads": backendUploads },
  });

  const frontend = service("hms-frontend-dev", {
    source: github(REPO, { branch: "develop", checkSuites: false, rootDirectory: "/hospital-portal" }),
    build: FRONTEND_BUILD,
    ...FRONTEND_HEALTHCHECK,
    replicas: ONE_REPLICA,
    deploy: { ...RESTART_ON_FAILURE_3 },
    domains: ["dev.e-keneya.com"],
  });

  const keycloak = service("hms-keycloak-dev", {
    source: github(REPO, { branch: "develop", checkSuites: false }),
    build: KEYCLOAK_BUILD,
    ...KEYCLOAK_HEALTHCHECK,
    replicas: ONE_REPLICA,
    deploy: { limitOverride: { containers: { cpu: 32, memoryBytes: 2000000000 } }, ...RESTART_ALWAYS },
  });

  return [
    keycloakDb,
    backend,
    frontend,
    appDb,
    keycloak,
    cache,
    backendUploads,
    keycloakDbVolume,
    detachedRedisVolume,
    appDbVolume,
    cacheVolume,
  ];
}

// ---- prod environment (deploys the main branch) ----

function prodResources() {
  const keycloakDb = postgresDb("hms-keycloak-prod-db", POSTGRES_18_IMAGE);
  keycloakDb.deploy = { multiRegionConfig: { [REGION]: { numReplicas: 1 } } };
  keycloakDb.networking = { privateNetworkEndpoint: "postgres" };

  const appDb = postgresDb("hms-Postgres-prod", POSTGRES_17_IMAGE);
  appDb.deploy = { multiRegionConfig: { [REGION]: { numReplicas: 1 } } };
  appDb.networking = { privateNetworkEndpoint: "hms-postgres-prod", tcpProxies: { "5432": {} } };

  const cache = redisDb("Redis");
  cache.deploy = { multiRegionConfig: { [REGION]: { numReplicas: 1 } }, startCommand: REDIS_START_COMMAND };
  cache.networking = { privateNetworkEndpoint: "redis" };

  // Volumes. hospital-core-volume (the uploads from before object storage) is currently
  // attached to nothing; it is listed so that apply does not delete it.
  const detachedUploadsVolume = volume("hospital-core-volume", VOLUME_50_GB);
  const appDbVolume = volume("postgres-volume", VOLUME_50_GB);
  const cacheVolume = volume("redis-volume", VOLUME_50_GB);
  const keycloakDbVolume = volume("postgres-volume-vFKN", VOLUME_50_GB);

  // Buckets (uploads, database point-in-time recovery). Listed so that apply keeps them.
  const bucketCompactEnvelope = bucket("compact-envelope-9gjM", { region: "iad" });
  const bucketPostgresPitr = bucket("Postgres-PITR", { region: "iad" });
  const bucketOptimizedSatchel = bucket("optimized-satchel-Z8fC", { region: "iad" });

  const keycloak = service("hms-keycloak-prod", {
    source: github(REPO, { branch: "main", checkSuites: false }),
    build: KEYCLOAK_BUILD,
    ...KEYCLOAK_HEALTHCHECK,
    replicas: ONE_REPLICA,
    // sleepApplication mirrors the dashboard as found on 2026-11-26; see the runbook.
    deploy: {
      limitOverride: { containers: { cpu: 32, memoryBytes: 2000000000 } },
      sleepApplication: true,
      ...RESTART_ALWAYS,
    },
    networking: { privateNetworkEndpoint: "prod" },
  });

  const frontend = service("hms-frontend-prod", {
    source: github(REPO, { branch: "main", checkSuites: false, rootDirectory: "/hospital-portal" }),
    build: FRONTEND_BUILD,
    ...FRONTEND_HEALTHCHECK,
    replicas: ONE_REPLICA,
    deploy: { ...RESTART_ON_FAILURE_3 },
    domains: ["e-keneya.com"],
  });

  const backend = service("hms-backend-core", {
    source: github(REPO, { branch: "main", checkSuites: false }),
    build: BACKEND_BUILD,
    ...BACKEND_HEALTHCHECK,
    replicas: ONE_REPLICA,
    deploy: { limitOverride: { containers: { cpu: 32, memoryBytes: 6000000000 } }, ...RESTART_ON_FAILURE_3 },
    domains: ["api.e-keneya.com"],
  });

  return [
    keycloakDb,
    appDb,
    keycloak,
    cache,
    frontend,
    backend,
    detachedUploadsVolume,
    appDbVolume,
    cacheVolume,
    keycloakDbVolume,
    bucketCompactEnvelope,
    bucketPostgresPitr,
    bucketOptimizedSatchel,
  ];
}

export default defineRailway((ctx) => {
  // Fail closed: planning this file against any other environment (a fork, a PR
  // environment) would otherwise offer to delete everything that environment holds.
  let resources;
  if (ctx.isEnvironment("dev")) {
    resources = devResources();
  } else if (ctx.isEnvironment("prod")) {
    resources = prodResources();
  } else {
    throw new Error(
      `.railway/railway.ts only describes the "dev" and "prod" environments, not "${ctx.environment}". ` +
        "Link the right one with `railway environment` before running plan or apply.",
    );
  }

  return project("e-keneya", {
    // Variables and secrets are managed in the Railway dashboard, never in this file.
    variables: { managed: false },
    resources,
  });
});
