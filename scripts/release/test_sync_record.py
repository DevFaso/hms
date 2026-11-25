"""Exercise real Git graphs in throwaway repositories; no network or deployment."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import io
import json
import unittest
from types import SimpleNamespace
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

    def test_changed_deleted_renamed_migrations_and_registration_are_never_hidden(self):
        self.git('checkout', '-b', 'migration-baseline', self.previous)
        directory = self.repo / m.MIGRATION_ROOT
        directory.mkdir(parents=True, exist_ok=True)
        for name in ['V1__modify.sql', 'V2__delete.sql', 'V3__rename.sql', 'changelog.xml']:
            (directory / name).write_text('original ' + name, encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'Existing migrations')
        self.git('checkout', '-b', 'migration-changes')
        (directory / 'V1__modify.sql').write_text('changed', encoding='utf-8')
        (directory / 'V2__delete.sql').unlink()
        (directory / 'V3__rename.sql').rename(directory / 'V4__renamed.sql')
        (directory / 'changelog.xml').write_text('registration changed', encoding='utf-8')
        self.git('add', '.')
        self.git('commit', '-m', 'Migration changes')
        tip = self.git('rev-parse', 'HEAD')
        self.git('checkout', 'migration-baseline')
        self.git('merge', '--no-ff', 'migration-changes', '-m', f'Sync develop {tip} into main (sync #11)')
        sync = self.git('rev-parse', 'HEAD')
        self.git('update-ref', 'refs/remotes/origin/main', sync)
        data = m.inspect_sync(sync, 11)
        statuses = [row[0][0] for row in data['migration_changes']]
        self.assertEqual(sorted(statuses), ['D', 'M', 'M', 'R'])
        rendered = m.render(data, [], {sync: [], tip: []})
        for name in ['V1__modify.sql', 'V2__delete.sql', 'V3__rename.sql', 'V4__renamed.sql', 'changelog.xml']:
            self.assertIn(name.replace('_', r'\_'), rendered)

    def test_event_selection_uses_triggering_head_and_normalizes_dispatch_hash(self):
        event = {'workflow_run': {'event': 'push', 'head_branch': 'main', 'head_sha': self.head}}
        self.assertEqual(m.select_event('workflow_run', event), (self.head, 10))
        self.assertEqual(m.select_event('workflow_dispatch', {}, self.head[:9], '10', 'refs/heads/main'), (self.head, 10))
        event['workflow_run']['head_sha'] = self.develop
        self.assertIsNone(m.select_event('workflow_run', event))
        event['workflow_run']['head_branch'] = 'develop'
        self.assertIsNone(m.select_event('workflow_run', event))
        self.assertIsNone(m.select_event('pull_request', {}))
        for number in ['', '0', '10\nsha=evil', '11']:
            with self.assertRaises(ValueError):
                m.select_event('workflow_dispatch', {}, self.head, number, 'refs/heads/main')
        output = self.repo / 'outputs'
        m.write_selection((self.head, 10), output)
        self.assertEqual(output.read_text(), f'created=true\nsha={self.head}\nnumber=10\n')

    def test_own_workflow_checks_are_excluded_but_other_pending_checks_remain(self):
        def fake_api(path):
            if '/check-runs?' in path:
                return [{'check_runs': [
                    {'name': 'record', 'status': 'in_progress', 'html_url': 'https://github.com/DevFaso/hms/runs/77', 'details_url': 'https://github.com/DevFaso/hms/actions/runs/123/job/4'},
                    {'name': 'Backend', 'status': 'queued', 'html_url': 'https://github.com/DevFaso/hms/actions/runs/1234/job/4'},
                ]}]
            return [[]]
        with patch.object(m, 'api', side_effect=fake_api):
            self.assertEqual(m.check_evidence(self.head, [123]), [('Backend', 'queued', 'https://github.com/DevFaso/hms/actions/runs/1234/job/4')])

    def test_bounded_snapshot_refreshes_pending_evidence_and_retains_timeout_state(self):
        data = {'main': 'main', 'develop': 'dev'}
        pending = [('CI', 'pending', None)]
        success = [('CI', 'success', None)]
        with patch.object(m, 'api', return_value=[{'workflow_runs': []}]) as inventory, patch.object(m, 'check_evidence', side_effect=[pending, success, success, success]), patch.object(m.time, 'sleep') as sleep:
            evidence, complete = m.evidence_snapshot(data, 120)
            self.assertTrue(complete)
            self.assertEqual(evidence['main'], success)
            self.assertEqual(inventory.call_count, 2)
            sleep.assert_called_once_with(60)
        with patch.object(m, 'api', return_value=[{'workflow_runs': []}]), patch.object(m, 'check_evidence', return_value=pending):
            evidence, complete = m.evidence_snapshot(data, 0)
            self.assertFalse(complete)
            self.assertEqual(evidence['main'], pending)

    def test_api_retries_only_transient_limits_without_exposing_responses(self):
        limited = SimpleNamespace(returncode=1, stderr='HTTP 403 secondary rate limit', stdout='')
        headers = SimpleNamespace(returncode=0, stderr='', stdout='HTTP/2 200\nRetry-After: 120\n')
        ok = SimpleNamespace(returncode=0, stderr='', stdout='[[]]')
        with patch.object(m.subprocess, 'run', side_effect=[limited, headers, ok]) as request, patch.object(m.time, 'sleep') as sleep:
            self.assertEqual(m.api('pulls'), [[]])
            self.assertEqual(request.call_count, 3)
            self.assertIn('HEAD', request.call_args_list[1].args[0])
            sleep.assert_called_once_with(120)
        denied = SimpleNamespace(returncode=1, stderr='HTTP 403 Forbidden private response', stdout='')
        with patch.object(m.subprocess, 'run', return_value=denied) as request, self.assertRaisesRegex(RuntimeError, 'permissions'):
            m.api('pulls')
        self.assertEqual(request.call_count, 1)

    def test_rate_limit_minimum_and_budget_never_leak_body(self):
        for retry, delay in [('1', 60), ('120', 120)]:
            limited = SimpleNamespace(returncode=1, stderr='HTTP 429 private response', stdout='Retry-After: ' + retry)
            ok = SimpleNamespace(returncode=0, stderr='', stdout='[[]]')
            with self.subTest(retry=retry), patch.object(m.subprocess, 'run', side_effect=[limited, ok]), patch.object(m.time, 'sleep') as sleep:
                self.assertEqual(m.api('pulls'), [[]])
                sleep.assert_called_once_with(delay)
            with patch.object(m.subprocess, 'run', return_value=limited), patch.object(m.time, 'sleep') as sleep, self.assertRaisesRegex(m.EvidenceBudgetExpired, '^GitHub evidence wait budget exhausted$'):
                m.api('pulls', deadline=m.time.monotonic() + 30)
            sleep.assert_not_called()

    def test_stacked_pr_fallback_verifies_merge_sha_and_names_missing_numbers(self):
        data = {'introduced': ['stacked-sha'], 'prs': [21]}
        def fake_api(path, **kwargs):
            self.assertNotIn('base=', path)
            if path.startswith('pulls?'):
                return [[]]
            return [{'number': 21, 'title': 'Stacked feature', 'merged_at': 'date', 'merge_commit_sha': 'stacked-sha'}]
        with patch.object(m, 'api', side_effect=fake_api):
            self.assertEqual(m.included_prs(data), [(21, 'Stacked feature')])
        with patch.object(m, 'api', side_effect=[[[]], [{'number': 21, 'merged_at': 'date', 'merge_commit_sha': 'outside'}]]), self.assertRaisesRegex(ValueError, 'missing: #21'):
            m.included_prs(data)

    def test_pr_inventory_stops_at_cutoff_without_loading_entire_history(self):
        data = {'introduced': ['wanted'], 'prs': [21], 'inventory_since': '2026-11-01T00:00:00+00:00'}
        rows = [{'number': 21, 'title': 'Feature', 'merged_at': 'date', 'merge_commit_sha': 'wanted', 'updated_at': '2026-10-01T00:00:00Z'}] * 100
        with patch.object(m, 'api', return_value=[rows]) as request:
            self.assertEqual(m.included_prs(data), [(21, 'Feature')])
            self.assertEqual(request.call_count, 1)
            self.assertIn('sort=updated', request.call_args.args[0])
            self.assertFalse(request.call_args.kwargs['paginate'])

    def test_invalid_wait_has_its_own_error_before_git_or_network(self):
        with patch('sys.argv', ['sync_record.py', '--wait-seconds', '901']), patch('sys.stderr', io.StringIO()) as errors, patch.object(m, 'inspect_sync') as inspect, self.assertRaises(SystemExit):
            m.main()
        self.assertIn('wait-seconds must be between 0 and 900', errors.getvalue())
        inspect.assert_not_called()

    def test_every_committed_sync_record_is_refreshable_and_reports_migration_changes(self):
        paths = list((Path(__file__).resolve().parents[2] / 'docs/releases').glob('*-sync-*.md'))
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(record=path.name):
                content = path.read_text(encoding='utf-8')
                self.assertIn('## Changed existing migrations and registration', content)
                self.assertIn(m.EVIDENCE_START + m.EVIDENCE_END, m.stable_content(content))


if __name__ == '__main__':
    unittest.main()
