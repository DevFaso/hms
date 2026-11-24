#!/usr/bin/env python3
"""Build an evidence record for an existing main sync. Never deploy or change refs."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

REPO = 'DevFaso/hms'
SYNC = re.compile(r'Sync develop ([0-9a-f]{7,40}) into main \(sync #(\d+)\)')
PR = re.compile(r'^Merge pull request #(\d+) from ')
MIGRATION = re.compile(r'^hospital-core/src/main/resources/db/migration/V\d+__[^/]+\.sql$')


def run(*args):
    return subprocess.check_output(args, text=True, encoding='utf-8').strip()


def git(*args):
    return run('git', *args)


def api(path):
    return json.loads(run('gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/{path}'))


def commit(ref):
    # An option or revision expression cannot escape the one commit argument.
    if not re.fullmatch(r'[0-9a-f]{7,40}', ref):
        raise ValueError('Use a main sync commit hash, not a branch or revision expression')
    return git('rev-parse', '--verify', ref + '^{commit}')


def inspect_sync(ref, number):
    head = commit(ref)
    parents = git('show', '-s', '--format=%P', head).split()
    match = SYNC.fullmatch(git('show', '-s', '--format=%s', head))
    if len(parents) != 2 or not match or int(match[2]) != number:
        raise ValueError('Expected a two-parent main sync with the requested sync number')
    develop = commit(match[1])
    if develop != parents[1]:
        raise ValueError('The recorded develop commit does not match the sync second parent')
    git('merge-base', '--is-ancestor', head, 'origin/main')
    rollback = parents[0]
    pull_requests = set()
    for line in git('log', '--format=%s', f'{rollback}..{develop}').splitlines():
        found = PR.match(line)
        if found:
            pull_requests.add(int(found[1]))
    migrations = [path for path in git('diff', '--name-only', '--diff-filter=A', rollback, head).splitlines() if MIGRATION.fullmatch(path)]
    date = datetime.fromisoformat(git('show', '-s', '--format=%cI', head)).astimezone(timezone.utc).date().isoformat()
    return dict(number=number, main=head, develop=develop, rollback=rollback, date=date, prs=sorted(pull_requests), migrations=sorted(migrations), introduced=git('rev-list', f'{rollback}..{develop}').splitlines())


def included_prs(data):
    introduced = set(data['introduced'])
    found = {}
    def associated(sha):
        return [pr for page in api(f'commits/{sha}/pulls?per_page=100') for pr in page]
    # Subject parsing alone misses squash and rebase merges. Query associations
    # on every introduced commit, then require the actual merge SHA in the range.
    with ThreadPoolExecutor(max_workers=4) as pool:
        for records in pool.map(associated, data['introduced']):
            for record in records:
                if record.get('merged_at') and record.get('merge_commit_sha') in introduced:
                    found[record['number']] = record['title']
    # Conventional merge subjects are an independent completeness check.
    if not set(data['prs']).issubset(found):
        raise ValueError('GitHub PR evidence does not cover every merge commit in the sync')
    return sorted(found.items())


def one_line(value):
    text = ' '.join(str(value).split()).replace('<', '&lt;').replace('>', '&gt;')
    text = text.replace(chr(92), chr(92) * 2)
    return re.sub(r'([\[\]()!*_`|])', lambda match: chr(92) + match[0], text)


def check_evidence(sha):
    rows = []
    for page in api(f'commits/{sha}/check-runs?per_page=100'):
        for check in page['check_runs']:
            rows.append((check['name'], check.get('conclusion') or check['status'], check.get('html_url')))
    seen_contexts = set()
    for page in api(f'commits/{sha}/statuses?per_page=100'):
        for status in page:
            if status['context'] in seen_contexts:
                continue
            seen_contexts.add(status['context'])
            rows.append((status['context'], status['state'], status.get('target_url')))
    return sorted(set(rows), key=lambda row: (row[0], row[1], row[2] or ''))


def render(data, prs, evidence):
    url = f'https://github.com/{REPO}'
    lines = [f'# Sync #{data["number"]} — {data["date"]}', '',
             'Generated from the existing Git graph and GitHub check evidence. This record does not deploy, merge, or certify clinical acceptance.', '',
             f'- Main commit: [`{data["main"]}`]({url}/commit/{data["main"]})',
             f'- Develop commit included: [`{data["develop"]}`]({url}/commit/{data["develop"]})',
             f'- Rollback point (previous main): [`{data["rollback"]}`]({url}/commit/{data["rollback"]})', '',
             'The rollback point identifies code only. Database migrations and data changes require the reviewed recovery runbook; this record never reverses them.', '',
             '## Included pull requests', '']
    lines.extend(f'- [#{number}]({url}/pull/{number}) — {one_line(title)}' for number, title in prs)
    if not prs:
        lines.append('No pull-request merge commits were found in the included range.')
    lines += ['', '## Added migrations', '']
    lines.extend(f'- [`{path}`]({url}/blob/{data["main"]}/{path})' for path in data['migrations'])
    if not data['migrations']:
        lines.append('No versioned SQL migrations were added.')
    for name, sha in [('Main', data['main']), ('Develop', data['develop'])]:
        lines += ['', f'## {name} CI and Sonar evidence', '', f'Commit: `{sha}`', '', '| Check | Observed state | Evidence |', '|---|---|---|']
        for check, state, link in evidence[sha]:
            # External status URLs are data from GitHub, never shell arguments or Markdown syntax.
            safe_link = link if link and re.fullmatch(r'https://[A-Za-z0-9./?=&_%:#~+@-]+', link) else f'{url}/commit/{sha}/checks'
            lines.append(f'| {one_line(check)} | {one_line(state)} | [Open]({safe_link}) |')
        if not evidence[sha]:
            lines.append('| No retained check evidence | Unknown | Inspect commit checks manually |')
    lines += ['', 'Check states are a snapshot of GitHub evidence, not an assertion that every check passed. Re-running generation refreshes that evidence.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sync-commit', required=True)
    parser.add_argument('--sync-number', type=int, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path('docs/releases'))
    args = parser.parse_args()
    if args.sync_number < 1:
        parser.error('sync-number must be positive')
    data = inspect_sync(args.sync_commit, args.sync_number)
    prs = included_prs(data)
    evidence = {sha: check_evidence(sha) for sha in [data['main'], data['develop']]}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f'{data["date"]}-sync-{args.sync_number}.md'
    output.write_text(render(data, prs, evidence), encoding='utf-8', newline='\n')
    print(output.as_posix())


if __name__ == '__main__':
    main()
