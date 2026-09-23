"""Syntax-check the inline <script> of each HTML file given, using `node --check`.

A syntax error in the page's one inline script does not fail loudly: the browser
renders the static HTML and silently runs nothing, so the app looks like it loaded
while every button is dead. That shipped once (a duplicate `const` in v1.4). This
runs on every APK build and should be run before any web deploy.

    python check.py index.html android/assets/index.html
"""
import os
import re
import subprocess
import sys
import tempfile

SCRIPT_RE = re.compile(r'<script>\n(.*?)</script>', re.S)


def check(path):
    try:
        html = open(path, encoding='utf-8').read()
    except OSError as e:
        print('  FAIL %s: %s' % (path, e))
        return False

    scripts = SCRIPT_RE.findall(html)
    if not scripts:
        print('  FAIL %s: no inline <script> found' % path)
        return False

    ok = True
    for i, body in enumerate(scripts):
        fd, tmp = tempfile.mkstemp(suffix='.js')
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as f:
                f.write(body)
            r = subprocess.run(['node', '--check', tmp],
                               capture_output=True, text=True)
            if r.returncode != 0:
                ok = False
                # node reports line numbers within the script, not the HTML file
                offset = html[:html.index(body)].count('\n')
                print('  FAIL %s (script %d, add ~%d to line numbers):'
                      % (path, i + 1, offset))
                for line in (r.stderr or '').strip().splitlines()[:12]:
                    print('    ' + line)
        finally:
            os.unlink(tmp)
    if ok:
        print('  ok   %s (%d script block(s), %d chars)'
              % (path, len(scripts), sum(len(s) for s in scripts)))
    return ok


if __name__ == '__main__':
    targets = sys.argv[1:] or ['index.html']
    print('syntax check:')
    if all(check(t) for t in targets):
        sys.exit(0)
    print('\nSyntax check failed - the page would load but do nothing.')
    sys.exit(1)
