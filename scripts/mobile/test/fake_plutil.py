#!/usr/bin/env python3
# A stand-in for macOS plutil, covering only what check-info-plist.sh calls:
#
#   plutil -lint FILE
#   plutil -extract KEYPATH (xml1|raw) -o - FILE
#
# run-tests.sh exports a `plutil` shell function running this only when no
# real plutil exists, so the script's tests run on Linux and Git Bash too. On
# the macOS runner the same tests run against the real plutil, which is what
# keeps this honest.
import plistlib
import sys


def die(msg, code=1):
    sys.stderr.write(msg + "\n")
    sys.exit(code)


def load(path):
    with open(path, "rb") as f:
        return plistlib.load(f)


def main(argv):
    if len(argv) == 2 and argv[0] == "-lint":
        try:
            load(argv[1])
        except Exception as e:  # noqa: BLE001 - mirror plutil's one-line verdict
            print("%s: %s" % (argv[1], e))
            return 1
        print("%s: OK" % argv[1])
        return 0

    if len(argv) == 5 and argv[0] == "-extract" and argv[3] == "-o" and argv[4] != "-":
        die("fake plutil: only -o - is supported", 2)
    if not (len(argv) == 6 and argv[0] == "-extract" and argv[3] == "-o" and argv[4] == "-"):
        die("fake plutil: unsupported invocation: %r" % (argv,), 2)
    keypath, fmt, path = argv[1], argv[2], argv[5]
    value = load(path)
    for part in keypath.split("."):
        if isinstance(value, dict) and part in value:
            value = value[part]
        elif isinstance(value, list) and part.isdigit() and int(part) < len(value):
            value = value[int(part)]
        else:
            die("Could not extract value, error: No value at that key path or invalid key path: %s" % keypath)
    if fmt == "xml1":
        sys.stdout.write(plistlib.dumps(value, fmt=plistlib.FMT_XML).decode("utf-8"))
        return 0
    if fmt == "raw":
        if isinstance(value, bool):
            print("true" if value else "false")
        elif isinstance(value, (str, int, float)):
            print(value)
        else:
            die("Could not extract value, error: Cannot convert to raw format")
        return 0
    die("fake plutil: unsupported format %s" % fmt, 2)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
