"""Inventory all dump-producing paths so a new producer cannot bypass the snapshot contract."""
import ast
from pathlib import Path
import unittest


class CanonicalSnapshotGuardTest(unittest.TestCase):
    def test_every_production_dump_uses_an_exported_snapshot(self):
        producers = []
        for path in Path(__file__).parent.glob('*.py'):
            if path.name.startswith('test_'):
                continue
            source = path.read_text(encoding='utf-8')
            for node in ast.walk(ast.parse(source)):
                if not isinstance(node, ast.Call):
                    continue
                constants = [part.value for part in ast.walk(node) if isinstance(part, ast.Constant) and isinstance(part.value, str)]
                if 'pg_dump' in constants:
                    producers.append(path.name)
                    self.assertIn('--snapshot=', constants, f'{path.name}:{node.lineno}: pg_dump bypasses the canonical MVCC snapshot')
        self.assertTrue(producers, 'The canonical producer must exist')

    def test_reconciliation_uses_physical_rows_and_pins_serialization(self):
        source = Path(__file__).with_name('restore_drill.py').read_text(encoding='utf-8')
        tree = ast.parse(source)
        functions = {node.name: ast.get_source_segment(source, node) for node in ast.walk(tree) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
        self.assertIn('CANONICAL', functions['fingerprint'])
        self.assertIn('FROM ONLY', functions['fingerprint'])
        self.assertIn('FROM ONLY', functions['references'])
        for term in ["IntervalStyle='postgres'", "bytea_output='hex'", "lc_monetary='C'", "DateStyle='ISO, YMD'", "c.relkind='r'"]:
            self.assertIn(term, source)
        self.assertIn('digest.update(block)', functions['restore'])
        self.assertIn('expected_sha256', functions['restore'])
        self.assertIn("db.fingerprint(snapshot['snapshot'])", functions['capture_bundle'])
        self.assertIn("db.references(snapshot['snapshot'])", functions['capture_bundle'])


if __name__ == '__main__':
    unittest.main()
