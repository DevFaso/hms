#!/usr/bin/env python3
"""Owner/O3-only engineering utility: reconstruct an existing offline dump; never part of the drill."""
import argparse
import json
from pathlib import Path
from restore_drill import Scratch, capture_bundle, local_path, sha256


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dump', type=Path, required=True)
    parser.add_argument('--postgres-image', default='postgres:18')
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--objects-index', type=Path, required=True)
    args = parser.parse_args(argv)
    source = local_path(args.source_dump, strict=True)
    bundle = local_path(args.bundle, strict=True)
    if (bundle / 'database.dump').exists() or (bundle / 'manifest.json').exists():
        raise ValueError('Refusing to overwrite a backup bundle')
    objects = json.loads(local_path(args.objects_index, strict=True).read_text(encoding='utf-8'))
    # No caller-chosen container name/token or DSN is accepted. The input is
    # streamed into a fresh container created and removed by this invocation.
    with Scratch(args.postgres_image) as db:
        db.restore(source, sha256(source))
        capture_bundle(db, bundle, objects)
    print('Canonical scratch bundle captured from the local dump; this is not production RPO evidence.')


if __name__ == '__main__':
    main()
