#!/usr/bin/env python3
"""Persist a generated sync record through a draft PR; never merge or ready it."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import sync_record as records


def invoke(*args):
    return subprocess.check_output(args, text=True, encoding='utf-8').strip()


def publish(data, generated):
    name = f'{data["date"]}-sync-{data["number"]}.md'
    source = generated / name
    content = source.read_text(encoding='utf-8')
    branch = f'codex/sync-record-{data["number"]}-{data["main"][:12]}'
    existing = records.api(f'pulls?state=all&head=DevFaso:{branch}&base=develop&per_page=100')[0]
    if existing:
        if len(existing) != 1:
            raise ValueError('Ambiguous release-record PR')
        # A repeat run is an artifact refresh, not a reason to reopen or overwrite a PR.
        return existing[0]['html_url']
    remote = subprocess.run(['git', 'ls-remote', '--exit-code', '--heads', 'origin', branch], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if remote.returncode == 0:
        # Recover a push-success/PR-create-failure without moving the existing branch.
        invoke('git', 'fetch', 'origin', branch)
        saved = invoke('git', 'show', f'FETCH_HEAD:docs/releases/{name}')
        if saved != content.strip():
            raise ValueError('Existing record branch differs; refusing to rewrite it')
    elif remote.returncode == 2:
        invoke('git', 'switch', '--create', branch, 'origin/develop')
        destination = Path('docs/releases') / name
        if destination.exists():
            raise ValueError('Release record already exists on develop')
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding='utf-8', newline='\n')
        invoke('git', 'config', 'user.name', 'github-actions[bot]')
        invoke('git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
        invoke('git', 'add', '--', destination.as_posix())
        invoke('git', 'commit', '-m', f'docs(release): record sync #{data["number"]}')
        invoke('git', 'push', 'origin', f'HEAD:refs/heads/{branch}')
    else:
        raise RuntimeError('Could not verify whether the record branch exists')
    body = ('Generated sync evidence, automated on behalf of Tiego. Includes the exact main/develop commits, PRs, migrations, check links and code rollback point. '
            'This is a draft for review; no deployment, merge or readiness change is performed.')
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', suffix='.md', delete=False) as stream:
        stream.write(body)
        body_file = Path(stream.name)
    try:
        return invoke('gh', 'pr', 'create', '--repo', records.REPO, '--base', 'develop', '--head', branch,
                      '--draft', '--title', f'docs(release): sync #{data["number"]} evidence', '--body-file', str(body_file))
    finally:
        # Delete only this explicitly created regular file, never a directory.
        body_file.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sync-commit', required=True)
    parser.add_argument('--sync-number', required=True, type=int)
    parser.add_argument('--generated-root', type=Path, default=Path('sync-record/docs/releases'))
    args = parser.parse_args()
    data = records.inspect_sync(args.sync_commit, args.sync_number)
    print(publish(data, args.generated_root))


if __name__ == '__main__':
    main()
