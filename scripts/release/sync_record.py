#!/usr/bin/env python3
"""Build an evidence record for an existing main sync. Never deploy or change refs."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import os
import time

REPO = 'DevFaso/hms'
SYNC = re.compile(r'Sync develop ([0-9a-f]{7,40}) into main \(sync #(\d+)\)')
PR = re.compile(r'^Merge pull request #(\d+) from ')
MIGRATION = re.compile(r'^hospital-core/src/main/resources/db/migration/V\d+__[^/]+\.sql$')
MIGRATION_ROOT = 'hospital-core/src/main/resources/db/migration/'
EVIDENCE_START = '<!-- sync-evidence:start -->'
EVIDENCE_END = '<!-- sync-evidence:end -->'
PENDING = {'pending', 'queued', 'in_progress', 'waiting', 'requested'}


def run(*args):
    return subprocess.check_output(args, text=True, encoding='utf-8', timeout=90).strip()


def git(*args):
    return run('git', *args)


def api(path):
    for attempt in range(4):
        try:
            result = subprocess.run(['gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/{path}'],
                                    text=True, encoding='utf-8', capture_output=True, timeout=90)
        except subprocess.TimeoutExpired:
            if attempt == 3:
                raise RuntimeError('GitHub evidence request timed out') from None
            time.sleep(2 ** attempt)
            continue
        if result.returncode == 0:
            return json.loads(result.stdout)
        # Never retry a plain authorization refusal or include response bodies/tokens.
        limited = 'HTTP 429' in result.stderr or ('HTTP 403' in result.stderr and
                  any(term in result.stderr.lower() for term in ['rate limit', 'secondary limit', 'abuse']))
        if not limited or attempt == 3:
            raise RuntimeError('GitHub evidence request failed; check permissions or rate limits')
        time.sleep(2 ** attempt)


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
    changes = [row.split('\t') for row in git('diff', '--name-status', '--find-renames',
               '--diff-filter=ACDMR', rollback, head, '--', MIGRATION_ROOT).splitlines()]
    migrations = [row[1] for row in changes if row[0] == 'A' and MIGRATION.fullmatch(row[1])]
    date = datetime.fromisoformat(git('show', '-s', '--format=%cI', head)).astimezone(timezone.utc).date().isoformat()
    return dict(number=number, main=head, develop=develop, rollback=rollback, date=date, prs=sorted(pull_requests), migrations=sorted(migrations), migration_changes=[row for row in changes if not (row[0] == 'A' and MIGRATION.fullmatch(row[1]))], introduced=git('rev-list', f'{rollback}..{develop}').splitlines())


def included_prs(data):
    introduced = set(data['introduced'])
    found = {}
    # One paginated inventory, not one request per branch commit. A rebase tip,
    # squash or ordinary merge must still occur in the introduced Git range.
    for page in api('pulls?state=closed&base=develop&per_page=100'):
        for record in page:
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


def check_evidence(sha, excluded_runs=()):
    rows = []
    for page in api(f'commits/{sha}/check-runs?per_page=100'):
        for check in page['check_runs']:
            links = [check.get('html_url') or '', check.get('details_url') or '']
            if any(f'/actions/runs/{run_id}/' in link + '/' for run_id in excluded_runs for link in links):
                continue
            rows.append((check['name'], check.get('conclusion') or check['status'], check.get('html_url')))
    seen_contexts = set()
    for page in api(f'commits/{sha}/statuses?per_page=100'):
        for status in page:
            if status['context'] in seen_contexts:
                continue
            seen_contexts.add(status['context'])
            rows.append((status['context'], status['state'], status.get('target_url')))
    return sorted(set(rows), key=lambda row: (row[0], row[1], row[2] or ''))


def evidence_snapshot(data, wait_seconds):
    deadline = time.monotonic() + wait_seconds
    while True:
        evidence = {}
        for sha in dict.fromkeys([data['main'], data['develop']]):
            own_runs = [run['id'] for page in api(f'actions/workflows/sync-record.yml/runs?head_sha={sha}&per_page=100') for run in page['workflow_runs']]
            evidence[sha] = check_evidence(sha, own_runs)
        pending = any(not rows or any(row[1] in PENDING for row in rows) for rows in evidence.values())
        if not pending or time.monotonic() >= deadline:
            return evidence, not pending
        time.sleep(min(15, max(0, deadline - time.monotonic())))


def stable_content(content):
    # Refresh only an explicitly delimited evidence block. Stable identity,
    # migration and PR sections may never be silently replaced.
    if content.count(EVIDENCE_START) != 1 or content.count(EVIDENCE_END) != 1:
        raise ValueError('Record must contain exactly one evidence block')
    before, rest = content.split(EVIDENCE_START)
    _, after = rest.split(EVIDENCE_END)
    return before + EVIDENCE_START + EVIDENCE_END + after


def select_event(event_name, event, sync_ref='', sync_number=''):
    if event_name == 'workflow_run':
        workflow = event['workflow_run']
        if workflow['head_branch'] != 'main' or workflow['event'] != 'push':
            return None
        sync_ref = workflow['head_sha']
    elif event_name != 'workflow_dispatch':
        return None
    sha = commit(sync_ref)
    match = SYNC.fullmatch(git('show', '-s', '--format=%s', sha))
    if not match and event_name == 'workflow_run':
        return None
    number = sync_number if event_name == 'workflow_dispatch' else match[2]
    if not re.fullmatch(r'[1-9][0-9]*', str(number)):
        raise ValueError('A positive sync number is required')
    data = inspect_sync(sha, int(number))
    return data['main'], data['number']


def write_selection(selection, output):
    with output.open('a', encoding='utf-8', newline='\n') as stream:
        stream.write('created=' + ('true' if selection else 'false') + '\n')
        if selection:
            stream.write(f'sha={selection[0]}\nnumber={selection[1]}\n')


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
    lines += ['', '## Changed existing migrations and registration', '']
    changes = data.get('migration_changes', [])
    lines.extend('- ' + one_line(' → '.join(row)) for row in changes)
    if not changes:
        lines.append('No other migration-directory changes were found.')
    lines += ['', EVIDENCE_START]
    for name, sha in [('Main', data['main']), ('Develop', data['develop'])]:
        lines += ['', f'## {name} CI and Sonar evidence', '', f'Commit: `{sha}`', '', '| Check | Observed state | Evidence |', '|---|---|---|']
        for check, state, link in evidence[sha]:
            # External status URLs are data from GitHub, never shell arguments or Markdown syntax.
            safe_link = link if link and re.fullmatch(r'https://[A-Za-z0-9./?=&_%:#~+@-]+', link) else f'{url}/commit/{sha}/checks'
            lines.append(f'| {one_line(check)} | {one_line(state)} | [Open]({safe_link}) |')
        if not evidence[sha]:
            lines.append('| No retained check evidence | Unknown | Inspect commit checks manually |')
    lines += ['', 'Check states are a snapshot of GitHub evidence, not an assertion that every check passed. Re-running generation refreshes that evidence.', EVIDENCE_END, '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sync-commit')
    parser.add_argument('--sync-number', type=int)
    parser.add_argument('--from-event', action='store_true')
    parser.add_argument('--wait-seconds', type=int, default=0)
    parser.add_argument('--output-dir', type=Path, default=Path('docs/releases'))
    args = parser.parse_args()
    if args.from_event:
        selected = select_event(os.environ['GITHUB_EVENT_NAME'], json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8')),
                                os.environ.get('SYNC_COMMIT', ''), os.environ.get('SYNC_NUMBER', ''))
        write_selection(selected, Path(os.environ['GITHUB_OUTPUT']))
        return
    if not args.sync_commit or not args.sync_number or args.sync_number < 1 or not 0 <= args.wait_seconds <= 900:
        parser.error('sync-number must be positive')
    data = inspect_sync(args.sync_commit, args.sync_number)
    prs = included_prs(data)
    evidence, complete = evidence_snapshot(data, args.wait_seconds)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f'{data["date"]}-sync-{args.sync_number}.md'
    output.write_text(render(data, prs, evidence), encoding='utf-8', newline='\n')
    print(output.as_posix())
    if args.wait_seconds and not complete:
        raise SystemExit('Evidence is still pending after the bounded wait; artifact retained, publication withheld. Retry after checks finish.')


if __name__ == '__main__':
    main()
