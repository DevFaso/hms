"""Every restore entrypoint must stay on a verified, pinned local Docker transport."""
import ast
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import restore_drill as m

LOCAL = 'unix:///var/run/docker.sock'

class LocalDockerTest(unittest.TestCase):
    def metadata(self, endpoint):
        calls=[]
        def run(args, **kwargs):
            calls.append(args)
            if args[1:]==['context','show']:value='selected'
            elif args[1:3]==['context','inspect']:value=json.dumps(endpoint)
            elif 'image' in args:value=json.dumps([{'Id':'sha256:'+'a'*64}])
            else:raise AssertionError('Unexpected daemon command')
            return SimpleNamespace(returncode=0,stdout=value.encode(),stderr=b'')
        return calls,run

    def test_remote_host_and_context_are_rejected_before_any_daemon_request(self):
        for host, context in [('ssh://operator@remote','selected'),('tcp://10.0.0.1:2376','selected'),
                              ('tcp://127.0.0.1:2375','selected'),('npipe:////remote/pipe/docker_engine','selected'),
                              ('','selected')]:
            calls,run=self.metadata('tcp://remote:2376')
            env={**os.environ,'DOCKER_HOST':host,'DOCKER_CONTEXT':context}
            with self.subTest(host=host),patch.object(m.os,'environ',env),patch.object(m.subprocess,'run',side_effect=run):
                with self.assertRaisesRegex(ValueError,'Remote Docker'):m.Scratch('unused-image')
            self.assertTrue(all(call[1]=='context' for call in calls),calls)

    def test_local_socket_or_named_pipe_and_explicit_host_are_accepted(self):
        for endpoint in [LOCAL,'unix:///run/user/1000/docker.sock','npipe:////./pipe/docker_engine','npipe:////./pipe/dockerDesktopLinuxEngine']:
            calls,run=self.metadata(endpoint)
            env={**os.environ,'DOCKER_HOST':'','DOCKER_CONTEXT':'selected'}
            with self.subTest(endpoint=endpoint),patch.object(m.os,'environ',env),patch.object(m.subprocess,'run',side_effect=run):
                db=m.Scratch('local-image')
                self.assertEqual(db.endpoint,endpoint)
                self.assertEqual(calls[-1][:3],['docker','--host',endpoint])
        for endpoint in ['unix://remote/docker.sock','unix:////remote/docker.sock','tcp://localhost:2375','ssh://localhost','npipe:////server/pipe/docker_engine']:
            self.assertFalse(m.is_local_endpoint(endpoint),endpoint)

    def test_later_context_switch_cannot_redirect_a_pinned_command_or_stream(self):
        env={**os.environ,'DOCKER_HOST':'tcp://remote:2376','DOCKER_CONTEXT':'remote','DOCKER_TLS_VERIFY':'1','DOCKER_CERT_PATH':'remote-certs'}
        with patch.object(m.os,'environ',env),patch.object(m.subprocess,'run') as run:
            m.docker('container','inspect','owned',endpoint=LOCAL)
            self.assertEqual(run.call_args.args[0][:3],['docker','--host',LOCAL])
            self.assertFalse({'DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'} & run.call_args.kwargs['env'].keys())
        with tempfile.TemporaryDirectory(prefix='cdx-local-stream-') as directory:
            archive=Path(directory)/'existing.dump';archive.write_bytes(b'synthetic archive')
            db=m.Scratch.__new__(m.Scratch);db.endpoint=LOCAL;db.name='owned'
            with patch.object(m.os,'environ',env),patch.object(db,'owned'),patch.object(m.subprocess,'Popen') as popen:
                process=popen.return_value.__enter__.return_value;process.stdin=io.BytesIO();process.wait.return_value=0
                db.restore(archive,m.sha256(archive))
                self.assertEqual(popen.call_args.args[0][:3],['docker','--host',LOCAL])
                self.assertNotIn('DOCKER_CONTEXT',popen.call_args.kwargs['env'])

    def test_all_streaming_daemon_paths_use_the_verified_command_and_clean_environment(self):
        path=Path(m.__file__);tree=ast.parse(path.read_text())
        calls=[node for node in ast.walk(tree) if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and isinstance(node.func.value,ast.Name) and node.func.value.id=='subprocess' and node.func.attr=='Popen']
        self.assertEqual(len(calls),3)
        for call in calls:
            self.assertIsInstance(call.args[0],ast.Call)
            self.assertEqual(call.args[0].func.id,'docker_command')
            self.assertTrue(any(k.arg=='env' and isinstance(k.value,ast.Call) and k.value.func.id=='docker_environment' for k in call.keywords))
        functions={node.name:node for node in ast.walk(tree) if isinstance(node,ast.FunctionDef)}
        self.assertFalse(any(isinstance(node,ast.Call) and isinstance(node.func,ast.Name) and node.func.id=='capture_bundle' for node in ast.walk(functions['drill'])))
        runbook=(Path(__file__).resolve().parents[2]/'docs/runbooks/dr-drill-log.md').read_text()
        self.assertIn('owner/O3 work',runbook)
        self.assertIn('only reads that bundle',runbook)

if __name__=='__main__':unittest.main()
