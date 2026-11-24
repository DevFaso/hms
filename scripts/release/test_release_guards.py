"""Guard the automated event, evidence-refresh and migration-inventory choke points."""
import ast
from pathlib import Path
import unittest


class ReleaseEvidenceGuardTest(unittest.TestCase):
    def test_automated_generation_waits_for_main_ci_and_uses_normalized_selection(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/sync-record.yml').read_text(encoding='utf-8')
        for required in ['workflow_run:', "workflows: ['Project Quality Gates']", 'types: [completed]', '--from-event', 'needs.select.outputs.sha', "'--wait-seconds', '900'", 'cancel-in-progress: false']:
            self.assertIn(required, workflow)
        self.assertNotIn('  push:', workflow)
        self.assertNotIn('inputs.sync_commit || github.sha', workflow)

    def test_every_inventory_includes_changes_and_publication_cannot_freeze_open_evidence(self):
        root = Path(__file__).parent
        source = (root / 'sync_record.py').read_text(encoding='utf-8')
        tree = ast.parse(source)
        inspect = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'inspect_sync')
        constants = [node.value for node in ast.walk(inspect) if isinstance(node, ast.Constant)]
        self.assertIn('--name-status', constants)
        self.assertIn('--diff-filter=ACDMR', constants)
        publisher = (root / 'publish_sync_record.py').read_text(encoding='utf-8')
        self.assertIn('records.stable_content(saved)', publisher)
        self.assertIn("existing[0]['state'] == 'closed'", publisher)
        self.assertNotIn("'--force'", publisher)


if __name__ == '__main__':
    unittest.main()
