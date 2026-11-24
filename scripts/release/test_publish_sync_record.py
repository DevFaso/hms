import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publish_record', Path(__file__).with_name('publish_sync_record.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class PublishDraftTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cdx-sync-record-')
        self.root = Path(self.temp.name).resolve()
        self.previous = Path.cwd()
        self.repo = self.root / 'work'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '--bare', str(self.root / 'remote.git')], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.chdir(self.repo)
        self.git('init', '-b', 'develop')
        self.git('config', 'user.name', 'cdx Test')
        self.git('config', 'user.email', 'cdx@example.invalid')
        self.git('commit', '--allow-empty', '-m', 'cdx baseline')
        self.git('remote', 'add', 'origin', str(self.root / 'remote.git'))
        self.git('push', 'origin', 'develop')
        self.data = {'date': '2026-11-23', 'number': 10, 'main': 'a' * 40}
        self.generated = self.root / 'generated'
        self.generated.mkdir()
        (self.generated / '2026-11-23-sync-10.md').write_text('# Synthetic sync record\n', encoding='utf-8')

    def tearDown(self):
        os.chdir(self.previous)
        self.assertTrue(self.root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
        self.assertTrue(self.root.name.startswith('cdx-sync-record-'))
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], text=True, stderr=subprocess.DEVNULL).strip()

    def test_pushes_only_one_record_then_creates_draft_and_never_rewrites_a_different_branch(self):
        calls = []
        def invoke(*args):
            calls.append(args)
            if args[0] == 'git':
                return self.git(*args[1:])
            self.assertEqual(args[:3], ('gh', 'pr', 'create'))
            self.assertIn('--draft', args)
            self.assertIn('develop', args)
            body = Path(args[args.index('--body-file') + 1]).read_text(encoding='utf-8')
            self.assertIn('automated on behalf of Tiego', body)
            return 'https://github.com/DevFaso/hms/pull/9999'
        with patch.object(p.records, 'api', return_value=[[]]), patch.object(p, 'invoke', side_effect=invoke):
            self.assertTrue(p.publish(self.data, self.generated).endswith('/9999'))
            first = self.git('rev-parse', 'HEAD')
            self.assertEqual(self.git('diff-tree', '--no-commit-id', '--name-only', '-r', first), 'docs/releases/2026-11-23-sync-10.md')
            self.assertTrue(p.publish(self.data, self.generated).endswith('/9999'))
            self.assertEqual(self.git('rev-parse', 'HEAD'), first)
            (self.generated / '2026-11-23-sync-10.md').write_text('different evidence', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'refusing to rewrite'):
                p.publish(self.data, self.generated)
            self.assertEqual(self.git('rev-parse', 'HEAD'), first)
        self.assertEqual(sum(args[:2] == ('git', 'push') for args in calls), 1)
        self.assertFalse(any('--force' in args or 'merge' in args or 'ready' in args for args in calls))

    def test_existing_pr_is_left_untouched_including_a_closed_one(self):
        with patch.object(p.records, 'api', return_value=[[{'html_url': 'https://github.com/DevFaso/hms/pull/9998', 'state': 'closed'}]]), patch.object(p, 'invoke') as invoke:
            self.assertTrue(p.publish(self.data, self.generated).endswith('/9998'))
            invoke.assert_not_called()

    def test_existing_develop_record_refreshes_only_evidence_or_creates_nothing(self):
        source = self.generated / '2026-11-23-sync-10.md'
        destination = Path('docs/releases') / source.name
        destination.parent.mkdir(parents=True)
        def content(state):
            return f'# Immutable sync\n{p.records.EVIDENCE_START}\n{state}\n{p.records.EVIDENCE_END}\n'
        destination.write_text(content('pending'), encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'cdx committed record')
        self.git('push', 'origin', 'develop')
        source.write_text(content('pending'), encoding='utf-8')
        calls = []
        def invoke(*args):
            calls.append(args)
            if args[0] == 'git':
                return self.git(*args[1:])
            self.assertEqual(args[:3], ('gh', 'pr', 'create'))
            self.assertIn('--draft', args)
            return 'https://github.com/DevFaso/hms/pull/9999'
        with patch.object(p.records, 'api', return_value=[[]]), patch.object(p, 'invoke', side_effect=invoke):
            before = self.git('rev-parse', 'HEAD')
            self.assertIn('/blob/develop/', p.publish(self.data, self.generated))
            self.assertEqual(self.git('rev-parse', 'HEAD'), before)
            self.assertFalse(any(args[:2] == ('git', 'push') or args[0] == 'gh' for args in calls))
            # The no-op creates a local branch but no remote branch.
            self.git('switch', 'develop')
            self.git('branch', '-D', 'codex/sync-record-10-' + 'a' * 12)
            source.write_text(content('success'), encoding='utf-8')
            self.assertTrue(p.publish(self.data, self.generated).endswith('/9999'))
            self.assertEqual(self.git('rev-parse', 'HEAD^'), before)
            self.assertEqual(p.records.stable_content(destination.read_text()), p.records.stable_content(content('pending')))

    def test_existing_develop_record_refuses_changed_identity(self):
        source = self.generated / '2026-11-23-sync-10.md'
        destination = Path('docs/releases') / source.name
        destination.parent.mkdir(parents=True)
        original = f'# Original\n{p.records.EVIDENCE_START}\npending\n{p.records.EVIDENCE_END}\n'
        destination.write_text(original, encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'cdx committed record')
        self.git('push', 'origin', 'develop')
        source.write_text(original.replace('Original', 'Altered'), encoding='utf-8')
        def invoke(*args):
            self.assertEqual(args[0], 'git', 'No PR may be opened for altered stable data')
            self.assertNotIn('push', args)
            return self.git(*args[1:])
        with patch.object(p.records, 'api', return_value=[[]]), patch.object(p, 'invoke', side_effect=invoke), self.assertRaisesRegex(ValueError, 'refusing to rewrite'):
            p.publish(self.data, self.generated)

    def test_failed_create_recovers_with_refreshed_evidence_and_open_pr_updates_fast_forward(self):
        source = self.generated / '2026-11-23-sync-10.md'
        def content(state):
            return f'# Same immutable sync\n{p.records.EVIDENCE_START}\n{state}\n{p.records.EVIDENCE_END}\n'
        source.write_text(content('pending'), encoding='utf-8')
        existing = []
        fail_create = True
        calls = []
        def invoke(*args):
            nonlocal fail_create
            calls.append(args)
            if args[0] == 'git':
                return self.git(*args[1:])
            self.assertIn('--draft', args)
            if fail_create:
                fail_create = False
                raise RuntimeError('simulated PR policy failure')
            existing.append({'html_url': 'https://github.com/DevFaso/hms/pull/9999', 'state': 'open', 'draft': True})
            return existing[0]['html_url']
        with patch.object(p.records, 'api', side_effect=lambda _: [existing]), patch.object(p, 'invoke', side_effect=invoke):
            with self.assertRaisesRegex(RuntimeError, 'policy'):
                p.publish(self.data, self.generated)
            first = self.git('rev-parse', 'HEAD')
            source.write_text(content('success'), encoding='utf-8')
            p.publish(self.data, self.generated)
            second = self.git('rev-parse', 'HEAD')
            self.assertEqual(self.git('rev-parse', second + '^'), first)
            self.assertEqual(self.git('show', f'{second}:docs/releases/{source.name}'), content('success').strip())
            source.write_text(content('failure'), encoding='utf-8')
            p.publish(self.data, self.generated)
            third = self.git('rev-parse', 'HEAD')
            self.assertEqual(self.git('rev-parse', third + '^'), second)
            self.assertEqual(sum(args[:3] == ('gh', 'pr', 'create') for args in calls), 2)
            source.write_text(content('failure').replace('immutable', 'altered'), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'refusing to rewrite'):
                p.publish(self.data, self.generated)
        self.assertFalse(any('--force' in args or 'merge' in args or 'ready' in args for args in calls))


if __name__ == '__main__':
    unittest.main()
