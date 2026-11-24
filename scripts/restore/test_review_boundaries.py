"""Behavioral guards for container attachment, restore I/O and scratch-only evidence."""
import copy
import json
import io
import capture_manifest as capture_cli
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import restore_drill as m


class RestoreReviewBoundaryTest(unittest.TestCase):
    def test_capture_cli_refuses_caller_supplied_container_identity_before_any_io(self):
        with patch.object(capture_cli, 'Scratch') as scratch, patch.object(capture_cli, 'local_path', side_effect=AssertionError('No filesystem access')), patch('sys.stderr', io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                capture_cli.main(['--source-dump', 'never-opened.dump', '--bundle', 'never-created',
                                  '--objects-index', 'never-opened.json', '--owned-container', 'existing', '--owner-token', 'forged'])
            self.assertEqual(error.exception.code, 2)
            scratch.assert_not_called()

    def test_only_declared_anonymous_local_volumes_are_accepted(self):
        db = m.Scratch.__new__(m.Scratch)
        db.name, db.token = m.PREFIX + 'a' * 32, 'a' * 32
        safe = {'Config': {'Labels': {m.LABEL: db.token}, 'Volumes': {'/var/lib/postgresql': {}}},
                'Name': '/' + db.name, 'HostConfig': {'NetworkMode': 'none'},
                'Mounts': [{'Type': 'volume', 'Driver': 'local', 'Name': 'b' * 64, 'Destination': '/var/lib/postgresql'}]}
        with patch.object(m, 'text', return_value=json.dumps([safe])):
            db.owned()
        for field, value in [('Type', 'bind'), ('Type', 'tmpfs'), ('Name', 'named-host-data'),
                             ('Driver', 'nfs'), ('Destination', '/host')]:
            inspected = copy.deepcopy(safe)
            inspected['Mounts'][0][field] = value
            with self.subTest(field=field, value=value), patch.object(m, 'text', return_value=json.dumps([inspected])):
                with self.assertRaisesRegex(ValueError, 'outside'):
                    db.owned()

    def test_an_early_restore_exit_has_the_same_generic_error_and_reaps_the_process(self):
        with tempfile.TemporaryDirectory(prefix='cdx-restore-pipe-') as directory:
            root = Path(directory).resolve(strict=True)
            self.assertTrue(root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
            archive = root / 'source.dump'
            archive.write_bytes(b'cdx archive')
            db = m.Scratch.__new__(m.Scratch)
            db.name = m.PREFIX + 'a' * 32
            with patch.object(db, 'owned'), patch.object(m.subprocess, 'Popen') as popen:
                process = popen.return_value.__enter__.return_value
                process.stdin.write.side_effect = BrokenPipeError('cdx private diagnostic')
                with self.assertRaisesRegex(RuntimeError, '^Scratch archive restore failed; no record data is logged$'):
                    db.restore(archive, m.sha256(archive))
                process.kill.assert_called_once()
                process.wait.assert_called_once()

    def test_ordered_postgres_timestamps_do_not_depend_on_the_host_clock_or_claim_production_rpo(self):
        with tempfile.TemporaryDirectory(prefix='cdx-restore-clock-') as directory:
            root = Path(directory).resolve(strict=True)
            self.assertTrue(root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
            archive = root / 'database.dump'
            archive.write_bytes(b'cdx archive')
            tables = {'clinical.rows': {'rows': 1, 'sha256': 'a' * 64}}
            metadata = {'snapshotAt': '2036-01-01T00:00:00Z', 'sourceObservedAt': '2036-01-01T00:00:10Z',
                        'sourceKind': m.SCRATCH_CAPTURE_KIND, 'tables': tables, 'objects': [], 'archiveSha256': m.sha256(archive)}
            (root / 'manifest.json').write_text(json.dumps(metadata))
            with patch.object(m, 'Scratch') as scratch:
                db = scratch.return_value.__enter__.return_value
                db.image = 'sha256:' + 'b' * 64
                db.fingerprint.return_value = tables
                db.references.return_value = []
                db.sql.return_value = '18.6'
                result = m.drill(root, 'unused-local-image', root / 'report.json')
            self.assertEqual(result['snapshotObservationLagSeconds'], 10)
            self.assertIsNone(result['rpoSeconds'])
            self.assertIn('not production', result['rpoBasis'])


if __name__ == '__main__':
    unittest.main()
