"""Exercise real Git graphs in throwaway repositories; no network or deployment."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('sync_record', Path(__file__).with_name('sync_record.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class SyncRecordTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'cdx Test')
        self.git('config', 'user.email', 'cdx@example.invalid')
        self.git('commit', '--allow-empty', '-m', 'Previous release')
        self.previous = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-b', 'develop')
        self.git('commit', '--allow-empty', '-m', 'Merge pull request #21 from DevFaso/cdx-feature')
        directory = self.repo / 'hospital-core/src/main/resources/db/migration'
        directory.mkdir(parents=True)
        (directory / 'V394__cdx_test.sql').write_text('SELECT 1;\n', encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'Merge pull request #22 from DevFaso/cdx-migration')
        self.develop = self.git('rev-parse', 'HEAD')
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', 'develop', '-m', f'Sync develop {self.develop[:9]} into main (sync #10)')
        self.head = self.git('rev-parse', 'HEAD')
        self.git('update-ref', 'refs/remotes/origin/main', self.head)
        self.runner = patch.object(m, 'run', side_effect=lambda *args: subprocess.check_output(args, cwd=self.repo, text=True).strip())
        self.runner.start()

    def tearDown(self):
        self.runner.stop()
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True, stderr=subprocess.DEVNULL).strip()

    def test_records_two_parents_complete_pr_range_and_added_migrations(self):
        data = m.inspect_sync(self.head, 10)
        self.assertEqual((data['main'], data['develop'], data['rollback']), (self.head, self.develop, self.previous))
        self.assertEqual(data['prs'], [21, 22])
        self.assertEqual(data['migrations'], ['hospital-core/src/main/resources/db/migration/V394__cdx_test.sql'])
        text = m.render(data, [(21, 'Feature'), (22, 'Migration')], {self.head: [('SonarCloud', 'failure', None)], self.develop: []})
        self.assertIn('| SonarCloud | failure |', text)
        self.assertIn('Unknown', text)
        self.assertIn(self.previous, text)
        self.assertNotIn('all checks passed', text)

    def test_refuses_wrong_number_non_sync_and_revision_injection(self):
        for ref, number in [(self.head, 11), (self.develop, 10), ('--all', 10), ('HEAD~1', 10)]:
            with self.subTest(ref=ref, number=number), self.assertRaises(ValueError):
                m.inspect_sync(ref, number)

    def test_refuses_incorrect_develop_hash_in_sync_subject(self):
        self.git('commit', '--amend', '-m', f'Sync develop {self.previous[:9]} into main (sync #10)')
        amended = self.git('rev-parse', 'HEAD')
        with self.assertRaisesRegex(ValueError, 'second parent'):
            m.inspect_sync(amended, 10)

    def test_includes_squash_rebase_and_merge_prs_but_not_open_or_previous_prs(self):
        data = {'introduced': ['new-a', 'new-b'], 'prs': [21]}
        rows = [
            {'number': 21, 'title': 'Merge', 'merged_at': 'date', 'merge_commit_sha': 'new-a'},
            {'number': 22, 'title': 'Squash', 'merged_at': 'date', 'merge_commit_sha': 'new-b'},
            {'number': 23, 'title': 'Open', 'merged_at': None, 'merge_commit_sha': 'new-b'},
            {'number': 24, 'title': 'Previous', 'merged_at': 'date', 'merge_commit_sha': 'old'},
        ]
        with patch.object(m, 'api', return_value=[rows]):
            self.assertEqual(m.included_prs(data), [(21, 'Merge'), (22, 'Squash')])
        with patch.object(m, 'api', return_value=[[]]), self.assertRaisesRegex(ValueError, 'every merge commit'):
            m.included_prs(data)

    def test_titles_are_plain_text_not_links_or_injected_report_rows(self):
        value = m.one_line('[fake](https://example.invalid) | <script> \n next')
        self.assertNotIn('[fake](', value)
        self.assertIn('\\[fake\\]', value)
        self.assertIn('\\|', value)
        self.assertNotIn('<script>', value)
        self.assertNotIn('\n', value)

    def test_collects_every_check_page_and_commit_status_including_sonar(self):
        def fake_api(path):
            if '/check-runs?' in path:
                return [{'check_runs': [{'name': 'CI', 'conclusion': 'success', 'html_url': 'https://github.com/DevFaso/hms/actions/runs/1'}]}, {'check_runs': [{'name': 'Unit', 'conclusion': None, 'status': 'in_progress', 'html_url': None}]}]
            return [[{'context': 'SonarCloud', 'state': 'pending', 'target_url': 'https://sonarcloud.io/dashboard?id=hms'}]]
        with patch.object(m, 'api', side_effect=fake_api):
            checks = m.check_evidence(self.head)
        self.assertEqual([row[0] for row in checks], ['CI', 'SonarCloud', 'Unit'])
        self.assertEqual(checks[-1][1], 'in_progress')


if __name__ == '__main__':
    unittest.main()
