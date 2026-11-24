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


if __name__ == '__main__':
    unittest.main()
