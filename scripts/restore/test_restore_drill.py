"""All database work is confined to fresh, labelled, network-isolated Docker containers."""
import hashlib
import importlib.util
import json
import os
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
            with self.assertRaisesRegex(ValueError, 'outside'):
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

    def test_real_seeded_backup_restores_counts_checksums_and_every_file_reference(self):
        (self.root / 'objects').mkdir()
        (self.root / 'objects/image.bin').write_bytes(b'cdx synthetic image')
        (self.root / 'objects/document.bin').write_bytes(b'cdx synthetic document')
        with m.Scratch(os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18')) as db:
            db.sql("CREATE SCHEMA clinical; CREATE SCHEMA imaging; CREATE TABLE clinical.patients (id int, name text, note text); INSERT INTO clinical.patients VALUES (1, 'cdx Aïcha', NULL), (1, 'cdx Aïcha', NULL), (2, 'cdx Patient', E'line1\\nline2'); CREATE TABLE clinical.documents (file_path text); INSERT INTO clinical.documents VALUES ('cdx/document'); CREATE TABLE imaging.images (storage_key text, storage_bucket text); INSERT INTO imaging.images VALUES ('cdx/image', 'synthetic'); CREATE TABLE clinical.empty_table (id int)")
            tables = db.fingerprint()
            refs = db.references()
            self.assertEqual(tables['clinical.patients']['rows'], 3)
            self.assertEqual(tables['clinical.empty_table']['rows'], 0)
            self.assertEqual(len(refs), 2)
            objects = []
            for schema, table, column, bucket, key, file in [
                ('clinical', 'documents', 'file_path', '', 'cdx/document', 'objects/document.bin'),
                ('imaging', 'images', 'storage_key', 'synthetic', 'cdx/image', 'objects/image.bin')]:
                reference = hashlib.sha256('\0'.join([schema, table, column, bucket, key]).encode()).hexdigest()
                objects.append({'reference': reference, 'path': file, 'sha256': m.sha256(self.root / file)})
            db.owned()
            with (self.root / 'database.dump').open('wb') as archive:
                subprocess.run(['docker', 'exec', db.name, 'pg_dump', '-U', 'postgres', '-d', 'hms_drill', '-Fc'], stdout=archive, stderr=subprocess.PIPE, check=True)
        observed = datetime.now(timezone.utc)
        metadata = {'snapshotAt': (observed - timedelta(seconds=60)).isoformat(), 'sourceObservedAt': observed.isoformat(), 'tables': tables, 'objects': objects, 'archiveSha256': m.sha256(self.root / 'database.dump')}
        (self.root / 'manifest.json').write_text(json.dumps(metadata), encoding='utf-8')
        result = m.drill(self.root, os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18'), self.root / 'success.json')
        self.assertEqual(result['tables'], tables)
        self.assertEqual(result['fileReferencesChecked'], 2)
        self.assertEqual(result['rpoSeconds'], 60)
        self.assertGreater(result['rtoSeconds'], 0)
        print('LOCAL_DRILL_EVIDENCE ' + json.dumps({key: result[key] for key in ['status', 'fileReferencesChecked', 'rtoSeconds', 'rpoSeconds', 'postgresVersion']}))
        # Production-only negative controls: corrupt baseline counts or one object.
        metadata['tables']['clinical.patients']['rows'] = 2
        (self.root / 'manifest.json').write_text(json.dumps(metadata), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'row counts'):
            m.drill(self.root, os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18'), self.root / 'bad-count.json')
        metadata['tables']['clinical.patients']['rows'] = 3
        (self.root / 'manifest.json').write_text(json.dumps(metadata), encoding='utf-8')
        (self.root / 'objects/image.bin').write_bytes(b'cdx damaged')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            m.drill(self.root, os.environ.get('HMS_DRILL_POSTGRES_IMAGE', 'postgres:18'), self.root / 'bad-file.json')
        self.assertFalse((self.root / 'bad-count.json').exists())
        self.assertFalse((self.root / 'bad-file.json').exists())


if __name__ == '__main__':
    unittest.main()
