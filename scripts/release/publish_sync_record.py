#!/usr/bin/env python3
"""Persist a generated sync record through a draft PR; never merge or ready it."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import sync_record as records


def invoke(*args):
    return subprocess.check_output(args, text=True, encoding='utf-8', timeout=90).strip()


def publish(data, generated):
    name = f'{data["date"]}-sync-{data["number"]}.md'
    source = generated / name
    content = source.read_text(encoding='utf-8')
    branch = f'codex/sync-record-{data["number"]}-{data["main"][:12]}'
    existing = records.api(f'pulls?state=all&head=DevFaso:{branch}&base=develop&per_page=100')[0]
    if existing:
        if len(existing) != 1:
            raise ValueError('Ambiguous release-record PR')
        if existing[0]['state'] == 'closed':
            return existing[0]['html_url']
    remote = subprocess.run(['git', 'ls-remote', '--exit-code', '--heads', 'origin', branch], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    destination = Path('docs/releases') / name
    if remote.returncode == 0:
        # Fetch the exact published parent. Updates are ordinary fast-forwards;
        # a racing writer causes a safe push failure, never a forced replacement.
        invoke('git', 'fetch', 'origin', branch)
        parent = invoke('git', 'rev-parse', 'FETCH_HEAD')
        base = invoke('git', 'merge-base', 'origin/develop', parent)
        changed = invoke('git', 'diff', '--name-only', base, parent).splitlines()
        if changed != [destination.as_posix()]:
            raise ValueError('Existing record branch contains unrelated changes')
        saved = invoke('git', 'show', f'{parent}:{destination.as_posix()}')
        if saved != content.strip():
            try:
                same_stable = records.stable_content(saved) == records.stable_content(content.strip())
            except ValueError:
                same_stable = False
            if not same_stable:
                raise ValueError('Stable record differs; refusing to rewrite it')
            invoke('git', 'switch', '--detach', parent)
            destination.write_text(content, encoding='utf-8', newline='\n')
            commit_and_push(destination, branch, data['number'])
    elif remote.returncode == 2:
        invoke('git', 'switch', '--create', branch, 'origin/develop')
        if destination.exists():
            saved = destination.read_text(encoding='utf-8')
            if saved.strip() == content.strip():
                return f'https://github.com/{records.REPO}/blob/develop/{destination.as_posix()}'
            if records.stable_content(saved) != records.stable_content(content):
                raise ValueError('Stable record differs; refusing to rewrite it')
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding='utf-8', newline='\n')
        commit_and_push(destination, branch, data['number'])
    else:
        raise RuntimeError('Could not verify whether the record branch exists')
    if existing:
        # Preserve the current review/readiness state, including externally readied PRs.
        return existing[0]['html_url']
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


def commit_and_push(destination, branch, number):
    invoke('git', 'config', 'user.name', 'github-actions[bot]')
    invoke('git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    invoke('git', 'add', '--', destination.as_posix())
    invoke('git', 'commit', '-m', f'docs(release): record sync #{number} evidence')
    invoke('git', 'push', 'origin', f'HEAD:refs/heads/{branch}')


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
