#!/usr/bin/env python3
"""Capture an offline bundle from a tool-owned isolated scratch source only."""
import argparse
import json
from pathlib import Path
from restore_drill import Scratch, capture_bundle, local_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owned-container', required=True)
    parser.add_argument('--owner-token', required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--objects-index', type=Path, required=True)
    args = parser.parse_args()
    db = Scratch.attach_owned(args.owned_container, args.owner_token)
    objects = json.loads(local_path(args.objects_index, strict=True).read_text(encoding='utf-8'))
    capture_bundle(db, args.bundle, objects)
    print('Canonical snapshot manifest and database.dump captured from the owned scratch source.')


if __name__ == '__main__':
    main()
