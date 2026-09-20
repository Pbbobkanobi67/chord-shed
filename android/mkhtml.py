"""Turn the artifact page into a standalone offline document for the APK's assets."""
import re

src = open('../index.html', encoding='utf-8').read()

# Drop the Google Fonts network links; the APK ships the faces as assets.
src = re.sub(r'<link rel="preconnect"[^>]*>\s*', '', src)
src = re.sub(r'<link rel="stylesheet" href="https://fonts\.googleapis\.com[^>]*>\s*', '', src)

title = re.search(r'<title>(.*?)</title>', src).group(1)
src = re.sub(r'<title>.*?</title>\s*', '', src, count=1)

HEAD = '''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>%s</title>
<link rel="stylesheet" href="fonts.css">
<style>
  :root{
    color-scheme: light dark;
    padding-top: env(safe-area-inset-top, 0px);
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }
  html, body{margin:0}
  img{max-width:100%%}
  [hidden]{display:none !important}
  /* Native-app affordances: no text selection or long-press callout on controls. */
  body{-webkit-user-select:none; user-select:none; -webkit-tap-highlight-color:transparent;
       -webkit-touch-callout:none; overscroll-behavior-y:none}
  input[type=text]{-webkit-user-select:text; user-select:text}
</style>
''' % title

out = HEAD + src.strip() + '\n</body>\n</html>\n'

seam = re.compile(r'</style>\s*\n\s*<div class="wrap">')
out, n = seam.subn('</style>\n</head>\n<body>\n<div class="wrap">', out, count=1)
assert n == 1, 'could not find the </style> -> .wrap seam'

open('assets/index.html', 'w', encoding='utf-8').write(out)

assert '<body>' in out and '</head>' in out, 'head/body wrapping failed'
assert 'fonts.googleapis' not in out, 'network font link still present'
assert out.count('<title>%s</title>' % title) == 1, 'document title duplicated'
assert '</script>' in out and 'Karplus' in out, 'app script missing'
print('assets/index.html written: %d bytes -- checks ok' % len(out))
