#!/usr/bin/env python3
"""Offline restore proof in a newly owned, network-isolated Postgres container only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

LABEL = 'com.devfaso.hms.restore-drill'
PREFIX = 'hms-restore-drill-'


def docker(*args, **kwargs):
    return subprocess.run(['docker', *args], check=True, **kwargs)


def text(*args):
    return docker(*args, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode('utf-8').strip()


def quoted(identifier):
    return '"' + identifier.replace('"', '""') + '"'


class Scratch:
    def __init__(self, image):
        self.token = uuid.uuid4().hex
        self.name = PREFIX + self.token
        # Resolve a locally installed image to its immutable ID: no pull or prod network.
        inspected = json.loads(text('image', 'inspect', image))[0]
        self.image = inspected['Id']
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', self.image):
            raise ValueError('An immutable local image ID is required')

    def owned(self):
        item = json.loads(text('container', 'inspect', self.name))[0]
        if (item.get('Config', {}).get('Labels', {}).get(LABEL) != self.token
                or item['Name'] != '/' + self.name
                or item['HostConfig']['NetworkMode'] != 'none'
                or item['HostConfig'].get('PortBindings')
                or item['HostConfig'].get('Binds')):
            raise ValueError('Refusing a container outside this isolated drill')

    def sql(self, sql):
        self.owned()
        return text('exec', self.name, 'psql', '-U', 'postgres', '-d', 'hms_drill', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql)

    def __enter__(self):
        docker('run', '--detach', '--pull=never', '--network=none', '--name', self.name,
               '--label', f'{LABEL}={self.token}', '--env', 'POSTGRES_DB=hms_drill',
               '--env', 'POSTGRES_HOST_AUTH_METHOD=trust', self.image,
               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            until = time.monotonic() + 60
            while time.monotonic() < until:
                try:
                    self.sql('SELECT 1')
                    return self
                except subprocess.CalledProcessError:
                    time.sleep(0.25)
            raise TimeoutError('Scratch Postgres did not become ready')
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *_):
        self.owned()
        docker('rm', '--force', '--volumes', self.name, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def restore(self, archive):
        self.owned()
        # Stream only into the freshly owned container; never mount a host path.
        with archive.open('rb') as source:
            docker('exec', '-i', self.name, 'pg_restore', '-U', 'postgres', '-d', 'hms_drill',
                   '--no-owner', '--no-privileges', '--exit-on-error', stdin=source,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def fingerprint(self):
        names = json.loads(self.sql("SELECT COALESCE(json_agg(x), '[]') FROM (SELECT table_schema AS schema, table_name AS name FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema') ORDER BY table_schema, table_name) x"))
        result = {}
        for table in names:
            self.owned()
            identifier = quoted(table['schema']) + '.' + quoted(table['name'])
            sql = f"SET TIME ZONE 'UTC'; SET extra_float_digits=3; COPY (SELECT row_to_json(t)::text FROM {identifier} t ORDER BY row_to_json(t)::text COLLATE \"C\") TO STDOUT"
            digest, count = hashlib.sha256(), 0
            with subprocess.Popen(['docker', 'exec', self.name, 'psql', '-U', 'postgres', '-d', 'hms_drill', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
                for row in process.stdout:
                    digest.update(row)
                    count += 1
                if process.wait() != 0:
                    raise RuntimeError('Table reconciliation failed; no record data is logged')
            result[table['schema'] + '.' + table['name']] = {'rows': count, 'sha256': digest.hexdigest()}
        return result

    def references(self):
        columns = json.loads(self.sql("SELECT COALESCE(json_agg(x), '[]') FROM (SELECT c.table_schema AS schema, c.table_name AS name, c.column_name AS column, EXISTS (SELECT 1 FROM information_schema.columns b WHERE b.table_schema=c.table_schema AND b.table_name=c.table_name AND b.column_name='storage_bucket') AS has_bucket FROM information_schema.columns c WHERE table_schema NOT IN ('pg_catalog','information_schema') AND (column_name IN ('storage_key','file_path','photo_file_path','letter_storage_path') OR column_name LIKE '%\\_storage_key' ESCAPE '\\') ORDER BY table_schema, table_name, column_name) x"))
        found = set()
        for column in columns:
            table = quoted(column['schema']) + '.' + quoted(column['name'])
            key = quoted(column['column'])
            bucket = "COALESCE(storage_bucket::text, '')" if column['has_bucket'] else "''"
            values = json.loads(self.sql(f"SELECT COALESCE(json_agg(x), '[]') FROM (SELECT DISTINCT {key}::text AS value, {bucket} AS bucket FROM {table} WHERE {key} IS NOT NULL AND {key}::text <> '') x"))
            for value in values:
                # A reference has table/column scope, so equal keys in different stores cannot alias.
                raw = '\0'.join([column['schema'], column['name'], column['column'], value['bucket'], value['value']])
                found.add(hashlib.sha256(raw.encode()).hexdigest())
        return sorted(found)


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
    if str(bundle).startswith(('\\\\', '//')) or str(report).startswith(('\\\\', '//')):
        raise ValueError('Use an offline local copy, never a network share')
    if report.exists():
        raise ValueError('Refusing to overwrite a drill report')
    bundle = bundle.resolve(strict=True)
    metadata = json.loads(safe_file(bundle, 'manifest.json').read_text(encoding='utf-8'))
    if not metadata.get('tables'):
        raise ValueError('A hospital restore needs a nonempty table inventory')
    archive = safe_file(bundle, 'database.dump')
    if sha256(archive) != metadata['archiveSha256']:
        raise ValueError('Database backup checksum did not match')
    backup_at = timestamp(metadata['snapshotAt'])
    observed_at = timestamp(metadata['sourceObservedAt'])
    if backup_at > observed_at or observed_at > datetime.now(timezone.utc):
        raise ValueError('Invalid backup observation timestamps')
    with Scratch(image) as db:
        db.restore(archive)
        actual = db.fingerprint()
        if actual != metadata['tables']:
            raise ValueError('Restored table inventory, row counts or checksums did not match')
        files = check_files(bundle, db.references(), metadata['objects'])
        version = db.sql('SHOW server_version')
    result = {'status': 'PASS', 'measuredAt': datetime.now(timezone.utc).isoformat(),
              'archiveSha256': metadata['archiveSha256'], 'postgresVersion': version,
              'tables': actual, 'fileReferencesChecked': files,
              'rtoSeconds': round(time.monotonic() - start, 3),
              'rpoSeconds': (observed_at - backup_at).total_seconds(),
              'rpoBasis': 'Source-observed time minus backup snapshot time; not a live production observation'}
    if report.exists():
        raise ValueError('Refusing to overwrite a drill report')
    report.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(result, indent=2) + '\n'
    with report.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(rendered)
    return result


def latest_bundle(root):
    if str(root).startswith(('\\\\', '//')):
        raise ValueError('Use an offline local copy, never a network share')
    root = root.resolve(strict=True)
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
