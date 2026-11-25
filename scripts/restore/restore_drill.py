#!/usr/bin/env python3
"""Offline restore proof in a newly owned, network-isolated Postgres container only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
from contextlib import contextmanager

LABEL = 'com.devfaso.hms.restore-drill'
PREFIX = 'hms-restore-drill-'
SCRATCH_CAPTURE_KIND = 'isolated-scratch-capture'
CANONICAL = "SET TIME ZONE 'UTC'; SET extra_float_digits=3; SET IntervalStyle='postgres'; SET bytea_output='hex'; SET lc_monetary='C'; SET DateStyle='ISO, YMD'; "
PHYSICAL = "c.relkind='r' AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'"


def is_local_endpoint(endpoint):
    if not isinstance(endpoint, str) or any(c in endpoint for c in ['\r', '\n', '?', '#', '@']):
        return False
    # No TCP (including loopback/forwarders), SSH, remote pipes or socket hosts.
    return bool(re.fullmatch(r'unix:///(?!/)[^\\]+', endpoint)
                or re.fullmatch(r'npipe:////[.]/pipe/[A-Za-z0-9_.-]+', endpoint))


def docker_environment():
    return {key: value for key, value in os.environ.items()
            if key.upper() not in {'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'}}


def local_docker_endpoint():
    supplied = os.environ.get('DOCKER_HOST')
    if supplied and not is_local_endpoint(supplied):
        raise ValueError('Remote Docker daemons are refused; use a local Unix socket or named pipe')
    # Context metadata is read locally; these commands do not contact the daemon.
    def metadata(*args):
        result = subprocess.run(['docker', 'context', *args], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30, check=False)
        if result.returncode:
            raise RuntimeError('Cannot verify the local Docker context')
        return result.stdout.decode('utf-8').strip()
    context = os.environ.get('DOCKER_CONTEXT') or metadata('show')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', context):
        raise ValueError('Cannot verify the selected Docker context')
    endpoint = json.loads(metadata('inspect', context, '--format', '{{json .Endpoints.docker.Host}}'))
    if not is_local_endpoint(endpoint):
        raise ValueError('Remote Docker contexts are refused; select a local Docker context')
    return endpoint if os.environ.get('DOCKER_CONTEXT') or not supplied else supplied


def docker_command(endpoint, *args):
    if not is_local_endpoint(endpoint):
        raise ValueError('Remote Docker daemons are refused')
    return ['docker', '--host', endpoint, *args]


def docker(*args, endpoint=None, **kwargs):
    return subprocess.run(docker_command(endpoint or local_docker_endpoint(), *args),
                          check=True, env=docker_environment(), **kwargs)


def text(*args, endpoint=None):
    return docker(*args, endpoint=endpoint, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode('utf-8').strip()


def quoted(identifier):
    return '"' + identifier.replace('"', '""') + '"'


class Scratch:
    def __init__(self, image):
        self.endpoint = local_docker_endpoint()
        self.token = uuid.uuid4().hex
        self.name = PREFIX + self.token
        # Resolve a locally installed image to its immutable ID: no pull or prod network.
        inspected = json.loads(text('image', 'inspect', image, endpoint=self.endpoint))[0]
        self.image = inspected['Id']
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', self.image):
            raise ValueError('An immutable local image ID is required')

    def owned(self):
        item = json.loads(text('container', 'inspect', self.name, endpoint=self.endpoint))[0]
        host = item['HostConfig']
        mounts = item.get('Mounts') or []
        unsafe_volume = any(
            mount.get('Type') != 'volume' or mount.get('Driver') != 'local'
            or not re.fullmatch(r'[0-9a-f]{64}', mount.get('Name', ''))
            or mount.get('Destination') not in (item.get('Config', {}).get('Volumes') or {})
            for mount in mounts)
        if (item.get('Config', {}).get('Labels', {}).get(LABEL) != self.token
                or item['Name'] != '/' + self.name
                or host['NetworkMode'] != 'none'
                or any(host.get(key) for key in ['PortBindings', 'Binds', 'Mounts', 'VolumesFrom',
                                                'Privileged', 'Devices', 'DeviceRequests', 'CapAdd'])
                or any(host.get(key) == 'host' for key in ['PidMode', 'IpcMode', 'UTSMode', 'UsernsMode'])
                or unsafe_volume):
            raise ValueError('Refusing a container outside this isolated drill')

    def sql(self, sql, snapshot=None):
        self.owned()
        sql = snapshot_sql(snapshot) + sql
        return text('exec', self.name, 'psql', '-U', 'postgres', '-d', 'hms_drill', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql, endpoint=self.endpoint)

    def __enter__(self):
        docker('run', '--detach', '--pull=never', '--network=none', '--name', self.name,
               '--label', f'{LABEL}={self.token}', '--env', 'POSTGRES_DB=hms_drill',
               '--env', 'POSTGRES_HOST_AUTH_METHOD=trust', self.image,
               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, endpoint=self.endpoint)
        try:
            until = time.monotonic() + 60
            while time.monotonic() < until:
                try:
                    self.sql('SELECT 1')
                    return self
                except subprocess.CalledProcessError:
                    time.sleep(0.25)
            raise TimeoutError('Scratch Postgres did not become ready')
        except BaseException as error:
            self.__exit__(type(error), error, error.__traceback__)
            raise

    def __exit__(self, exception_type=None, error=None, traceback=None):
        try:
            self.owned()
            docker('rm', '--force', '--volumes', self.name, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, endpoint=self.endpoint)
        except Exception:
            # Never delete an unverified container or replace the useful drill error.
            if error is not None:
                error.add_note('Scratch cleanup could not verify/remove its container; inspect locally before cleanup.')
            else:
                raise RuntimeError('Scratch cleanup could not verify/remove its container') from None

    def restore(self, archive, expected_sha256):
        self.owned()
        # Stream only into the freshly owned container; never mount a host path.
        digest = hashlib.sha256()
        with archive.open('rb') as source, subprocess.Popen(
                docker_command(self.endpoint, 'exec', '-i', self.name, 'pg_restore', '-U', 'postgres', '-d', 'hms_drill',
                 '--no-owner', '--no-privileges', '--exit-on-error'), stdin=subprocess.PIPE, env=docker_environment(),
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) as process:
            try:
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    process.stdin.write(block)
                    digest.update(block)
                process.stdin.close()
                if process.wait() != 0:
                    raise RuntimeError('Scratch archive restore failed; no record data is logged')
            except OSError:
                process.kill()
                process.wait()
                raise RuntimeError('Scratch archive restore failed; no record data is logged') from None
            except BaseException:
                process.kill()
                process.wait()
                raise
        if digest.hexdigest() != expected_sha256:
            raise ValueError('Streamed database backup checksum did not match')

    def fingerprint(self, snapshot=None):
        names = json.loads(self.sql(f"SELECT COALESCE(json_agg(x), '[]') FROM (SELECT n.nspname AS schema, c.relname AS name FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE {PHYSICAL} ORDER BY n.nspname, c.relname) x", snapshot))
        result = {}
        for table in names:
            self.owned()
            identifier = quoted(table['schema']) + '.' + quoted(table['name'])
            sql = snapshot_sql(snapshot) + CANONICAL + f"COPY (SELECT row_to_json(t)::text FROM ONLY {identifier} t ORDER BY row_to_json(t)::text COLLATE \"C\") TO STDOUT"
            digest, count = hashlib.sha256(), 0
            with subprocess.Popen(docker_command(self.endpoint, 'exec', self.name, 'psql', '-U', 'postgres', '-d', 'hms_drill', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=docker_environment()) as process:
                for row in process.stdout:
                    digest.update(row)
                    count += 1
                if process.wait() != 0:
                    raise RuntimeError('Table reconciliation failed; no record data is logged')
            result[table['schema'] + '.' + table['name']] = {'rows': count, 'sha256': digest.hexdigest()}
        return result

    def references(self, snapshot=None):
        columns = json.loads(self.sql(f"SELECT COALESCE(json_agg(x), '[]') FROM (SELECT a.table_schema AS schema, a.table_name AS name, a.column_name AS column, EXISTS (SELECT 1 FROM information_schema.columns b WHERE b.table_schema=a.table_schema AND b.table_name=a.table_name AND b.column_name='storage_bucket') AS has_bucket FROM information_schema.columns a JOIN pg_namespace n ON n.nspname=a.table_schema JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=a.table_name WHERE {PHYSICAL} AND (column_name IN ('storage_key','file_path','photo_file_path','letter_storage_path') OR column_name LIKE '%\\_storage_key' ESCAPE '\\') ORDER BY table_schema, table_name, column_name) x", snapshot))
        found = set()
        for column in columns:
            table = quoted(column['schema']) + '.' + quoted(column['name'])
            key = quoted(column['column'])
            bucket = "COALESCE(storage_bucket::text, '')" if column['has_bucket'] else "''"
            values = json.loads(self.sql(f"SELECT COALESCE(json_agg(x), '[]') FROM (SELECT DISTINCT {key}::text AS value, {bucket} AS bucket FROM ONLY {table} WHERE {key} IS NOT NULL AND {key}::text <> '') x", snapshot))
            for value in values:
                # A reference has table/column scope, so equal keys in different stores cannot alias.
                raw = '\0'.join([column['schema'], column['name'], column['column'], value['bucket'], value['value']])
                found.add(hashlib.sha256(raw.encode()).hexdigest())
        return sorted(found)

    @contextmanager
    def snapshot(self):
        self.owned()
        with subprocess.Popen(docker_command(self.endpoint, 'exec', '-i', self.name, 'psql', '-U', 'postgres', '-d', 'hms_drill', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'),
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf-8', env=docker_environment()) as holder:
            try:
                holder.stdin.write(CANONICAL + "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT json_build_object('snapshot', pg_export_snapshot(), 'at', transaction_timestamp());\n")
                holder.stdin.flush()
                info = json.loads(holder.stdout.readline())
                snapshot_sql(info['snapshot'])
                yield info
                # Both timestamps come from the same PostgreSQL clock. A host/VM
                # clock comparison cannot measure the capture interval reliably.
                holder.stdin.write("SELECT json_build_object('observedAt', clock_timestamp()); ROLLBACK;\n")
                holder.stdin.flush()
                info['observedAt'] = json.loads(holder.stdout.readline())['observedAt']
                holder.stdin.close()
                if holder.wait(timeout=30) != 0:
                    raise RuntimeError('Snapshot holder failed')
            finally:
                if holder.poll() is None:
                    holder.kill()
                    holder.wait()


def snapshot_sql(snapshot):
    if snapshot is None:
        return ''
    if not re.fullmatch(r'[0-9A-Fa-f-]+', snapshot):
        raise ValueError('Invalid exported snapshot identifier')
    return f"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '{snapshot}'; "


def capture_bundle(db, bundle, objects):
    """Owner/O3-only producer for an owned scratch source; never called by the drill."""
    db.owned()
    bundle = local_path(bundle, strict=True)
    archive = bundle / 'database.dump'
    manifest = bundle / 'manifest.json'
    if archive.exists() or manifest.exists():
        raise ValueError('Refusing to overwrite a backup bundle')
    with db.snapshot() as snapshot:
        tables = db.fingerprint(snapshot['snapshot'])
        required = db.references(snapshot['snapshot'])
        check_files(bundle, required, objects)
        db.owned()
        with archive.open('xb') as stream:
            docker('exec', db.name, 'pg_dump', '-U', 'postgres', '-d', 'hms_drill', '-Fc', '--snapshot=' + snapshot['snapshot'],
                   stdout=stream, stderr=subprocess.DEVNULL, endpoint=db.endpoint)
    metadata = {'snapshotAt': snapshot['at'], 'sourceObservedAt': snapshot['observedAt'],
                'sourceKind': SCRATCH_CAPTURE_KIND,
                'tables': tables, 'objects': objects, 'archiveSha256': sha256(archive)}
    with manifest.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(metadata, indent=2) + '\n')
    return metadata


def local_path(path, strict=False):
    if str(path).startswith(('\\\\', '//')):
        raise ValueError('Use a local copy; UNC network share paths are rejected')
    resolved = path.resolve(strict=strict)
    if str(resolved).startswith(('\\\\', '//')):
        raise ValueError('Use a local copy; UNC network share paths are rejected')
    return resolved


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def safe_file(root, relative):
    if not isinstance(relative, str) or not relative or '\\' in relative or ':' in relative:
        raise ValueError('A relative bundle file path is required')
    candidate = Path(relative)
    if candidate.is_absolute() or '..' in candidate.parts:
        raise ValueError('File path must remain inside the offline bundle')
    path = (root / candidate).resolve(strict=True)
    if not path.is_relative_to(root.resolve(strict=True)) or not path.is_file():
        raise ValueError('File escaped the offline backup bundle')
    return path


def check_files(root, required, objects):
    if len({item['reference'] for item in objects}) != len(objects):
        raise ValueError('Duplicate file-reference entries')
    by_reference = {item['reference']: item for item in objects}
    if set(required) - by_reference.keys():
        raise ValueError('Restored document/image references are absent from the bundle manifest')
    for reference in required:
        entry = by_reference[reference]
        path = safe_file(root, entry['path'])
        if sha256(path) != entry['sha256']:
            raise ValueError('A document/image checksum did not match')
    return len(required)


def timestamp(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.utcoffset() is None:
        raise ValueError('Backup timestamps must include a timezone')
    return result.astimezone(timezone.utc)


def drill(bundle, image, report):
    start = time.monotonic()
    bundle = local_path(bundle, strict=True)
    report = local_path(report)
    if report.exists():
        raise ValueError('Refusing to overwrite a drill report')
    metadata = json.loads(safe_file(bundle, 'manifest.json').read_text(encoding='utf-8'))
    if not metadata.get('tables'):
        raise ValueError('A hospital restore needs a nonempty table inventory')
    archive = safe_file(bundle, 'database.dump')
    if sha256(archive) != metadata['archiveSha256']:
        raise ValueError('Database backup checksum did not match')
    backup_at = timestamp(metadata['snapshotAt'])
    observed_at = timestamp(metadata['sourceObservedAt'])
    if backup_at > observed_at:
        raise ValueError('Invalid backup observation timestamps')
    with Scratch(image) as db:
        db.restore(archive, metadata['archiveSha256'])
        actual = db.fingerprint()
        if actual != metadata['tables']:
            raise ValueError('Restored table inventory, row counts or checksums did not match')
        files = check_files(bundle, db.references(), metadata['objects'])
        version = db.sql('SHOW server_version')
    result = {'status': 'PASS', 'measuredAt': datetime.now(timezone.utc).isoformat(),
              'archiveSha256': metadata['archiveSha256'], 'postgresVersion': version,
              'postgresImageId': db.image, 'snapshotAt': metadata['snapshotAt'],
              'sourceObservedAt': metadata['sourceObservedAt'], 'bundle': bundle.name,
              'tables': actual, 'fileReferencesChecked': files,
              'rtoSeconds': round(time.monotonic() - start, 3),
              'sourceKind': metadata.get('sourceKind', 'supplied-backup'),
              'snapshotObservationLagSeconds': (observed_at - backup_at).total_seconds(),
              'rpoSeconds': None if metadata.get('sourceKind') == SCRATCH_CAPTURE_KIND else (observed_at - backup_at).total_seconds(),
              'rpoBasis': ('Scratch capture interval only; not production exposure or RPO evidence'
                           if metadata.get('sourceKind') == SCRATCH_CAPTURE_KIND else
                           'Supplied source cutoff minus supplied backup time; owner must verify clocks and provenance')}
    if report.exists():
        raise ValueError('Refusing to overwrite a drill report')
    report.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(result, indent=2) + '\n'
    with report.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(rendered)
    return result


def latest_bundle(root):
    root = local_path(root, strict=True)
    candidates = []
    for directory in root.iterdir():
        if not directory.is_dir() or not (directory / 'manifest.json').is_file():
            continue
        if not directory.resolve(strict=True).is_relative_to(root):
            raise ValueError('Backup directory escaped its offline root')
        metadata = json.loads(safe_file(directory, 'manifest.json').read_text(encoding='utf-8'))
        candidates.append((timestamp(metadata['snapshotAt']), directory))
    if not candidates:
        raise ValueError('No backup manifests were found')
    return max(candidates, key=lambda entry: (entry[0], entry[1].name))[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--bundle', type=Path)
    source.add_argument('--backup-root', type=Path)
    parser.add_argument('--local-postgres-image', default='postgres:18')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle if args.bundle else latest_bundle(args.backup_root)
    result = drill(bundle, args.local_postgres_image, args.report)
    print(json.dumps({key: result[key] for key in ['status', 'fileReferencesChecked', 'rtoSeconds', 'rpoSeconds']}))


if __name__ == '__main__':
    main()
