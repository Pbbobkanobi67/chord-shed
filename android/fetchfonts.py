import re, os, urllib.request

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36')
URL = ('https://fonts.googleapis.com/css2?family=Bodoni+Moda:opsz,wght@6..96,400;6..96,600;6..96,700'
       '&family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600&display=swap')

def get(u, binary=False):
    r = urllib.request.Request(u, headers={'User-Agent': UA})
    d = urllib.request.urlopen(r, timeout=60).read()
    return d if binary else d.decode('utf-8')

css = get(URL)
blocks = re.findall(r'@font-face\s*\{[^}]*\}', css)
print('total @font-face blocks:', len(blocks))

def ranges(block):
    m = re.search(r'unicode-range:\s*([^;]+);', block)
    if not m: return []
    out = []
    for part in m.group(1).split(','):
        part = part.strip().replace('U+', '')
        if '-' in part:
            a, b = part.split('-'); out.append((int(a, 16), int(b, 16)))
        else:
            v = int(part, 16); out.append((v, v))
    return out

def covers(block, cp):
    return any(a <= cp <= b for a, b in ranges(block))

os.makedirs('assets', exist_ok=True)
kept, seen = [], {}
for b in blocks:
    fam = re.search(r"font-family:\s*'([^']+)'", b).group(1)
    wt  = re.search(r'font-weight:\s*(\d+)', b).group(1)
    url = re.search(r'src:\s*url\(([^)]+)\)', b).group(1)
    # latin base (has the space char 0x20) or the subset holding the accidentals
    want_latin = covers(b, 0x41) and covers(b, 0x20)
    want_acc   = covers(b, 0x266D) and covers(b, 0x266F)
    if not (want_latin or want_acc):
        continue
    slug = fam.lower().replace(' ', '') + '-' + wt + ('-acc' if want_acc and not want_latin else '')
    n = seen.get(slug, 0); seen[slug] = n + 1
    name = slug + ('' if n == 0 else '-%d' % n) + '.woff2'
    data = get(url, binary=True)
    open('assets/' + name, 'wb').write(data)
    kept.append((fam, wt, name, re.search(r'unicode-range:\s*([^;]+);', b).group(1), len(data)))
    print('%-16s %s  %-30s %6d B  %s' % (fam, wt, name, len(data), 'accidentals' if want_acc else 'latin'))

out = ['/* Bundled subsets: latin + the block containing U+266D-266F (music accidentals) */']
for fam, wt, name, ur, _ in kept:
    out.append("@font-face{font-family:'%s';font-style:normal;font-weight:%s;font-display:swap;"
               "src:url(%s) format('woff2');unicode-range:%s;}" % (fam, wt, name, ur))
open('fonts.css', 'w', encoding='utf-8').write('\n'.join(out))
print('\nkept %d faces, %.0f KB total' % (len(kept), sum(k[4] for k in kept) / 1024))
