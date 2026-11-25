"""Public record boundaries: no deployment identifiers and main-only manual selection."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('public_sync', Path(__file__).with_name('sync_record.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class PublicRecordTest(unittest.TestCase):
    def test_renderer_omits_infrastructure_links_labels_and_free_form_pr_titles(self):
        sha = 'a' * 40
        identifier = '11111111-2222-3333-4444-555555555555'
        rows = [('Backend', 'success', 'https://github.com/DevFaso/hms/actions/runs/123/job/456'),
                ('Sonar', 'failure', 'https://sonarcloud.io/dashboard?id=DevFaso_hms&branch=main'),
                ('Deployment', 'success', 'https://railway.com/project/'+identifier+'/service/'+identifier+'?environmentId='+identifier),
                ('Service '+identifier, 'success', 'https://github.com/DevFaso/hms/runs/456'),
                ('Foreign', 'success', 'https://example.invalid/'+identifier),
                ('Encoded', 'success', 'https://github.com/DevFaso/hms/actions/runs/123?environmentId='+identifier)]
        data = {'number':10,'date':'2026-11-23','main':sha,'develop':'b'*40,'rollback':'c'*40,'migrations':[]}
        text = m.render(data, [(21, 'Private deployment '+identifier)], {sha:rows,'b'*40:[]})
        self.assertNotIn(identifier,text)
        self.assertNotIn('railway.',text)
        self.assertNotIn('Private deployment',text)
        self.assertIn('[#21](https://github.com/DevFaso/hms/pull/21)',text)
        self.assertIn('https://github.com/DevFaso/hms/actions/runs/123/job/456',text)
        self.assertIn('https://sonarcloud.io/dashboard?id=DevFaso_hms&branch=main',text)
        self.assertIn('| Sonar | failure |',text)
        self.assertIn('Unknown',text)

    def test_link_policy_refuses_provider_host_spoofs_credentials_and_unknown_sonar_projects(self):
        for link in ['https://github.com.evil.invalid/DevFaso/hms/runs/1',
                     'https://user:password@github.com/DevFaso/hms/runs/1',
                     'https://sonarcloud.io/dashboard?id=private-project',
                     'https://github.com:bad/DevFaso/hms/runs/1',
                     'https://railway.app/project/hidden', 'http://github.com/DevFaso/hms/runs/1']:
            with self.subTest(link=link):self.assertIsNone(m.public_evidence_link(link,'a'*40))
        self.assertEqual(m.public_evidence_link(None,'a'*40),'https://github.com/DevFaso/hms/commit/'+'a'*40+'/checks')

    def test_non_main_manual_selection_refuses_before_git_or_other_io(self):
        for ref in [None,'','refs/heads/develop','refs/heads/feature','refs/tags/main']:
            with self.subTest(ref=ref), patch.object(m,'git') as git:
                with self.assertRaisesRegex(ValueError,'refs/heads/main'):
                    m.select_event('workflow_dispatch',{},'a'*40,'10',ref)
                git.assert_not_called()

    def test_real_event_entrypoint_refuses_non_main_before_writing_any_selection(self):
        with tempfile.TemporaryDirectory(prefix='cdx-public-record-') as directory:
            event=Path(directory)/'event.json';event.write_text('{}');output=Path(directory)/'outputs'
            env={'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_EVENT_PATH':str(event),
                 'GITHUB_REF':'refs/heads/develop','GITHUB_OUTPUT':str(output),'SYNC_COMMIT':'a'*40,'SYNC_NUMBER':'10'}
            with patch.object(m.os,'environ',{**os.environ,**env}),patch('sys.argv',['sync_record.py','--from-event']),patch.object(m,'git') as git:
                with self.assertRaisesRegex(ValueError,'refs/heads/main'):m.main()
                git.assert_not_called()
            self.assertFalse(output.exists())

    def test_every_committed_release_record_and_manual_workflow_keep_the_public_boundary(self):
        root=Path(__file__).resolve().parents[2]
        for path in (root/'docs/releases').glob('*.md'):
            self.assertNotRegex(path.read_text(encoding='utf-8'),r'https://(?:www\.)?railway\.(?:com|app)/')
        workflow=(root/'.github/workflows/sync-record.yml').read_text()
        self.assertIn('workflow_dispatch:',workflow)
        self.assertIn('refuse-non-main-dispatch:',workflow)
        self.assertIn("github.ref != 'refs/heads/main'",workflow)
        self.assertGreaterEqual(workflow.count("github.ref == 'refs/heads/main'"),3)
        self.assertNotIn('deploy.yml',workflow)
        self.assertNotIn('railway ',workflow.lower())

if __name__=='__main__':unittest.main()
