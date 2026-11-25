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

    def test_reconciliation_calls_use_canonical_physical_rows_and_same_clock_metadata(self):
        source = Path(__file__).with_name('restore_drill.py').read_text(encoding='utf-8')
        tree = ast.parse(source)
        functions = {node.name: node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef)}
        assignments = {node.targets[0].id: ast.literal_eval(node.value) for node in tree.body
                       if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                       and isinstance(node.value, ast.Constant)}
        canonical = assignments['CANONICAL']
        for term in ["IntervalStyle='postgres'", "bytea_output='hex'", "lc_monetary='C'", "DateStyle='ISO, YMD'", "extra_float_digits=3", "TIME ZONE 'UTC'"]:
            self.assertIn(term, canonical)
        self.assertIn("c.relkind='r'", assignments['PHYSICAL'])
        for method in ['fingerprint', 'references']:
            constants = [node.value for node in ast.walk(functions[method]) if isinstance(node, ast.Constant) and isinstance(node.value, str)]
            self.assertTrue(any('FROM ONLY' in value for value in constants), method)
        serializing = [node for node in ast.walk(functions['fingerprint']) if isinstance(node, ast.Assign)
                       and any(isinstance(part, ast.Constant) and isinstance(part.value, str) and 'row_to_json' in part.value
                               for part in ast.walk(node.value))]
        self.assertTrue(serializing)
        self.assertTrue(all(any(isinstance(part, ast.Name) and part.id == 'CANONICAL' for part in ast.walk(node.value)) for node in serializing))
        query_names = {target.id for node in serializing for target in node.targets if isinstance(target, ast.Name)}
        launched = [node for node in ast.walk(functions['fingerprint']) if isinstance(node, ast.Call)
                    and isinstance(node.func, ast.Attribute) and node.func.attr == 'Popen']
        self.assertTrue(any(any(isinstance(part, ast.Name) and part.id in query_names for part in ast.walk(node)) for node in launched))
        for name in ['fingerprint', 'references']:
            calls = [node for node in ast.walk(functions['capture_bundle']) if isinstance(node, ast.Call)
                     and isinstance(node.func, ast.Attribute) and node.func.attr == name]
            self.assertTrue(calls)
            self.assertTrue(all(node.args and isinstance(node.args[0], ast.Subscript)
                                and isinstance(node.args[0].value, ast.Name) and node.args[0].value.id == 'snapshot'
                                and isinstance(node.args[0].slice, ast.Constant) and node.args[0].slice.value == 'snapshot' for node in calls))
        self.assertFalse(any(isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                             and isinstance(node.func.value, ast.Name) and node.func.value.id == 'datetime'
                             for node in ast.walk(functions['capture_bundle'])))
        self.assertTrue(any(isinstance(node, ast.Constant) and isinstance(node.value, str) and 'clock_timestamp()' in node.value for node in ast.walk(functions['snapshot'])))

    def test_no_production_entrypoint_accepts_a_caller_named_container_or_token(self):
        for path in Path(__file__).parent.glob('*.py'):
            if path.name.startswith('test_'):
                continue
            tree = ast.parse(path.read_text(encoding='utf-8'))
            for node in ast.walk(tree):
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                    self.assertNotEqual(node.func.attr, 'attach_owned', path.name)
                    if node.func.attr == 'add_argument':
                        options = [arg.value for arg in node.args if isinstance(arg, ast.Constant)]
                        self.assertNotIn('--owned-container', options)
                        self.assertNotIn('--owner-token', options)
        tree = ast.parse(Path(__file__).with_name('capture_manifest.py').read_text())
        owned_sources = [node for node in ast.walk(tree) if isinstance(node, ast.With)
                         and any(isinstance(item.context_expr, ast.Call) and isinstance(item.context_expr.func, ast.Name)
                                 and item.context_expr.func.id == 'Scratch' for item in node.items)]
        self.assertEqual(len(owned_sources), 1)
        calls = [node for node in ast.walk(owned_sources[0]) if isinstance(node, ast.Call)]
        restored = [node.lineno for node in calls if isinstance(node.func, ast.Attribute) and node.func.attr == 'restore']
        captured = [node.lineno for node in calls if isinstance(node.func, ast.Name) and node.func.id == 'capture_bundle']
        self.assertTrue(restored and captured)
        self.assertLess(max(restored), min(captured))


if __name__ == '__main__':
    unittest.main()
