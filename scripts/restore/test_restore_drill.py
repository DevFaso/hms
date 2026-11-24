"""All database work is confined to fresh, labelled, network-isolated Docker containers."""
import hashlib
import importlib.util
import json
import os
import io
import sys
from unittest.mock import patch
from pathlib import Path
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

spec = importlib.util.spec_from_file_location('restore_drill', Path(__file__).with_name('restore_drill.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class OfflineBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cdx-hms-drill-')
        self.root = Path(self.temp.name).resolve(strict=True)
        self.assertIn('cdx-hms-drill-', self.root.name)

    def tearDown(self):
        # Only the absolute directory created by this test can be removed.
        self.assertTrue(self.root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
        self.assertTrue(self.root.name.startswith('cdx-hms-drill-'))
        self.temp.cleanup()

    def test_refuses_escape_absolute_path_missing_and_changed_object(self):
        (self.root / 'image.bin').write_bytes(b'cdx image')
        for value in ['../outside', '/etc/passwd', 'C:/Windows/file', r'..\escape', 'missing']:
            with self.subTest(value=value), self.assertRaises((ValueError, FileNotFoundError)):
                m.safe_file(self.root, value)
        entry = {'reference': 'ref', 'path': 'image.bin', 'sha256': m.sha256(self.root / 'image.bin')}
        self.assertEqual(m.check_files(self.root, ['ref'], [entry]), 1)
        with self.assertRaisesRegex(ValueError, 'absent'):
            m.check_files(self.root, ['missing-ref'], [entry])
        (self.root / 'image.bin').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            m.check_files(self.root, ['ref'], [entry])
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            m.check_files(self.root, ['ref'], [entry, entry])

    def test_latest_backup_uses_snapshot_time_and_cleanup_refuses_an_unowned_container(self):
        for name, date in [('z-older', '2026-11-22T12:00:00Z'), ('a-newer', '2026-11-23T12:00:00Z')]:
            directory = self.root / name
            directory.mkdir()
            (directory / 'manifest.json').write_text(json.dumps({'snapshotAt': date}), encoding='utf-8')
        self.assertEqual(m.latest_bundle(self.root).name, 'a-newer')
        db = m.Scratch.__new__(m.Scratch)
        db.name, db.token = 'hms-restore-cdx', 'expected-owner'
        inspected = [{'Config': {'Labels': {m.LABEL: 'another-owner'}}, 'Name': '/hms-restore-cdx', 'HostConfig': {'NetworkMode': 'none'}}]
        with patch.object(m, 'text', return_value=json.dumps(inspected)), patch.object(m, 'docker') as docker:
            with self.assertRaisesRegex(RuntimeError, 'cleanup'):
                db.__exit__()
            docker.assert_not_called()

    def test_rejects_empty_inventory_existing_reports_and_network_paths_before_any_container(self):
        (self.root / 'manifest.json').write_text(json.dumps({'tables': {}}), encoding='utf-8')
        with patch.object(m, 'Scratch') as scratch:
            with self.assertRaisesRegex(ValueError, 'nonempty table inventory'):
                m.drill(self.root, 'unused-image', self.root / 'result.json')
            (self.root / 'result.json').write_text('existing evidence', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'overwrite'):
                m.drill(self.root, 'unused-image', self.root / 'result.json')
            with self.assertRaisesRegex(ValueError, 'network share'):
                m.drill(Path('//server/backup'), 'unused-image', self.root / 'other.json')
            with patch.object(Path, 'resolve', side_effect=AssertionError('No network filesystem access')):
                with self.assertRaisesRegex(ValueError, 'network share'):
                    m.latest_bundle(Path('//server/backup'))
            scratch.assert_not_called()
        self.assertEqual((self.root / 'result.json').read_text(encoding='utf-8'), 'existing evidence')

    def test_rejects_ambiguous_backup_time(self):
        with self.assertRaises(ValueError):
            m.timestamp('2026-11-23T08:00:00')
        self.assertEqual(m.timestamp('2026-11-23T08:00:00Z').utcoffset(), timedelta(0))

    def test_symlink_escape_is_rejected(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'record').write_text('cdx outside bundle', encoding='utf-8')
        bundle = self.root / 'bundle'
        bundle.mkdir()
        try:
            (bundle / 'escaped').symlink_to(outside, target_is_directory=True)
        except OSError as error:
            if os.name != 'nt' or error.winerror != 1314:
                raise
            # Windows without symlink privilege can still exercise a real
            # reparse-point escape using a directory junction. No shell deletion.
            subprocess.run(['cmd', '/c', 'mklink', '/J', str(bundle / 'escaped'), str(outside)], check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, 'escaped'):
            m.safe_file(bundle, 'escaped/record')

    def test_each_container_isolation_property_is_required_and_original_error_survives(self):
        db = m.Scratch.__new__(m.Scratch)
        db.name, db.token = m.PREFIX + 'a' * 32, 'a' * 32
        for field, value in [('PortBindings', {'5432/tcp': [{}]}), ('Binds', ['/host:/data']), ('NetworkMode', 'bridge'), ('Mounts', [{'Type': 'bind', 'Source': '/host'}]), ('VolumesFrom', ['other']), ('Privileged', True), ('Devices', [{'PathOnHost': '/dev/sda'}]), ('DeviceRequests', [{'Driver': 'nvidia'}]), ('CapAdd', ['SYS_ADMIN']), ('PidMode', 'host'), ('IpcMode', 'host'), ('UTSMode', 'host'), ('UsernsMode', 'host')]:
            inspected = {'Config': {'Labels': {m.LABEL: db.token}}, 'Name': '/' + db.name, 'HostConfig': {'NetworkMode': 'none'}}
            inspected['HostConfig'][field] = value
            with self.subTest(field=field), patch.object(m, 'text', return_value=json.dumps([inspected])), self.assertRaisesRegex(ValueError, 'outside'):
                db.owned()
        original = ValueError('table count mismatch')
        with patch.object(db, 'owned', side_effect=RuntimeError('inspect failed')), patch.object(m, 'docker') as docker:
            db.__exit__(ValueError, original, None)
            self.assertIn('Scratch cleanup', original.__notes__[0])
            docker.assert_not_called()
        self.assertFalse(hasattr(m.Scratch, 'attach_owned'), 'No arbitrary container attach API remains')

    def test_resolved_unc_path_is_rejected(self):
        with patch.object(Path, 'resolve', return_value=Path('//server/backup')), self.assertRaisesRegex(ValueError, 'network share'):
            m.local_path(Path('local-link'))

    def test_archive_checksum_mismatch_prevents_container_creation(self):
        (self.root / 'manifest.json').write_text(json.dumps({'tables': {'clinical.patients': {'rows': 1}}, 'archiveSha256': '0' * 64}), encoding='utf-8')
        (self.root / 'database.dump').write_bytes(b'changed dump')
        with patch.object(m, 'Scratch') as scratch, self.assertRaisesRegex(ValueError, 'checksum'):
            m.drill(self.root, 'unused', self.root / 'result.json')
        scratch.assert_not_called()

    def test_restore_verifies_the_bytes_actually_streamed(self):
        archive = self.root / 'changed-after-initial-hash.dump'
        archive.write_bytes(b'cdx original archive')
        original = m.sha256(archive)
        archive.write_bytes(b'cdx substituted archive')
        db = m.Scratch.__new__(m.Scratch)
        db.name = m.PREFIX + 'a' * 32
        with patch.object(db, 'owned'), patch.object(m.subprocess, 'Popen') as popen:
            process = popen.return_value.__enter__.return_value
            process.stdin = io.BytesIO()
            process.wait.return_value = 0
            with self.assertRaisesRegex(ValueError, 'Streamed.*checksum'):
                db.restore(archive, original)

    def test_real_seeded_backup_restores_counts_checksums_and_every_file_reference(self):
        (self.root / 'objects').mkdir()
        (self.root / 'objects/image.bin').write_bytes(b'cdx synthetic image')
        (self.root / 'objects/document.bin').write_bytes(b'cdx synthetic document')
        with m.Scratch(os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18')) as db:
            db.sql("CREATE SCHEMA clinical; CREATE SCHEMA imaging; CREATE TABLE clinical.patients (id int, name text, note text); INSERT INTO clinical.patients VALUES (1, 'cdx Aïcha', NULL), (1, 'cdx Aïcha', NULL), (2, 'cdx Patient', E'line1\\nline2'); CREATE TABLE clinical.documents (file_path text); INSERT INTO clinical.documents VALUES ('cdx/document'); CREATE TABLE imaging.images (storage_key text, storage_bucket text); INSERT INTO imaging.images VALUES ('cdx/image', 'synthetic'); CREATE TABLE clinical.empty_table (id int)")
            db.sql("CREATE VIEW imaging.reference_view AS SELECT storage_key FROM imaging.images; CREATE TABLE clinical.partition_docs (id int, file_path text) PARTITION BY RANGE (id); CREATE TABLE clinical.partition_docs_one PARTITION OF clinical.partition_docs FOR VALUES FROM (0) TO (100); INSERT INTO clinical.partition_docs VALUES (1, 'cdx/document'); CREATE TABLE clinical.formats (duration interval, bytes bytea, amount money, stamp timestamptz, value double precision, day date); INSERT INTO clinical.formats VALUES ('1 year 2 months 3 days 4:05:06', decode('00aaff', 'hex'), 123.45, '2026-11-23T13:00:00Z', 0.12345678901234567, '2026-11-23'); ALTER DATABASE hms_drill SET IntervalStyle='iso_8601'; ALTER DATABASE hms_drill SET bytea_output='escape'; ALTER DATABASE hms_drill SET TimeZone='Pacific/Kiritimati'; ALTER DATABASE hms_drill SET DateStyle='German, DMY'; ALTER DATABASE hms_drill SET lc_monetary='en_US.utf8'; ALTER DATABASE hms_drill SET extra_float_digits=0")
            self.assertEqual(db.sql('SHOW TimeZone'), 'Pacific/Kiritimati')
            self.assertEqual(db.sql('SHOW DateStyle'), 'German, DMY')
            self.assertEqual(db.sql('SHOW extra_float_digits'), '0')
            self.assertNotEqual(db.sql('SHOW lc_monetary'), 'C')
            tables = db.fingerprint()
            refs = db.references()
            self.assertEqual(tables['clinical.patients']['rows'], 3)
            self.assertEqual(tables['clinical.empty_table']['rows'], 0)
            self.assertEqual(len(refs), 3)
            self.assertNotIn('clinical.partition_docs', tables)
            self.assertNotIn('imaging.reference_view', tables)
            self.assertEqual(tables['clinical.partition_docs_one']['rows'], 1)
            objects = []
            for schema, table, column, bucket, key, file in [
                ('clinical', 'documents', 'file_path', '', 'cdx/document', 'objects/document.bin'),
                ('imaging', 'images', 'storage_key', 'synthetic', 'cdx/image', 'objects/image.bin'),
                ('clinical', 'partition_docs_one', 'file_path', '', 'cdx/document', 'objects/document.bin')]:
                reference = hashlib.sha256('\0'.join([schema, table, column, bucket, key]).encode()).hexdigest()
                objects.append({'reference': reference, 'path': file, 'sha256': m.sha256(self.root / file)})
            original_fingerprint = db.fingerprint
            def concurrent_write(snapshot):
                db.sql("INSERT INTO clinical.patients VALUES (3, 'cdx after snapshot', NULL)")
                return original_fingerprint(snapshot)
            with patch.object(db, 'fingerprint', side_effect=concurrent_write), patch.object(m, 'datetime') as host_clock:
                host_clock.now.side_effect = AssertionError('Capture must use the PostgreSQL clock only')
                metadata = m.capture_bundle(db, self.root, objects)
            self.assertEqual(metadata['tables'], tables)
            self.assertEqual(db.sql('SELECT count(*) FROM clinical.patients'), '4')
        result = m.drill(self.root, os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18'), self.root / 'success.json')
        self.assertEqual(result['tables'], tables)
        self.assertEqual(result['fileReferencesChecked'], 3)
        self.assertIsNone(result['rpoSeconds'])
        self.assertEqual(result['sourceKind'], m.SCRATCH_CAPTURE_KIND)
        self.assertGreaterEqual(result['snapshotObservationLagSeconds'], 0)
        self.assertRegex(result['postgresImageId'], r'^sha256:[0-9a-f]{64}$')
        self.assertEqual(result['snapshotAt'], metadata['snapshotAt'])
        self.assertEqual(result['bundle'], self.root.name)
        self.assertGreater(result['rtoSeconds'], 0)
        print('LOCAL_DRILL_EVIDENCE ' + json.dumps({key: result[key] for key in ['status', 'fileReferencesChecked', 'rtoSeconds', 'rpoSeconds', 'snapshotObservationLagSeconds', 'postgresVersion']}))
        # The public capture CLI creates its own isolated source; no name/token attachment.
        copied = self.root / 'cli-copy'
        (copied / 'objects').mkdir(parents=True)
        for file in ['image.bin', 'document.bin']:
            (copied / 'objects' / file).write_bytes((self.root / 'objects' / file).read_bytes())
        index = self.root / 'objects-index.json'
        index.write_text(json.dumps(objects), encoding='utf-8')
        subprocess.run([sys.executable, str(Path(__file__).with_name('capture_manifest.py')),
                        '--source-dump', str(self.root / 'database.dump'), '--bundle', str(copied),
                        '--objects-index', str(index), '--postgres-image', os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18')],
                       check=True, capture_output=True)
        copied_manifest = json.loads((copied / 'manifest.json').read_text())
        self.assertEqual(copied_manifest['tables'], tables)
        self.assertEqual(copied_manifest['sourceKind'], m.SCRATCH_CAPTURE_KIND)
        self.assertGreaterEqual(m.timestamp(copied_manifest['sourceObservedAt']), m.timestamp(copied_manifest['snapshotAt']))

        # Production-only negative controls: corrupt baseline counts or one object.
        metadata['tables']['clinical.patients']['rows'] = 2
        (self.root / 'manifest.json').write_text(json.dumps(metadata), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'row counts'):
            m.drill(self.root, os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18'), self.root / 'bad-count.json')
        metadata['tables']['clinical.patients']['rows'] = 3
        metadata['tables']['missing.table'] = {'rows': 0, 'sha256': '0' * 64}
        (self.root / 'manifest.json').write_text(json.dumps(metadata), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'table inventory'):
            m.drill(self.root, os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18'), self.root / 'bad-table.json')
        del metadata['tables']['missing.table']
        (self.root / 'manifest.json').write_text(json.dumps(metadata), encoding='utf-8')
        (self.root / 'objects/image.bin').write_bytes(b'cdx damaged')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            m.drill(self.root, os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18'), self.root / 'bad-file.json')
        self.assertFalse((self.root / 'bad-count.json').exists())
        self.assertFalse((self.root / 'bad-file.json').exists())


if __name__ == '__main__':
    unittest.main()
