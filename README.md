# Chord Shed

Chord diagrams, fingerings and voicings up the neck — for ukulele and guitar.

**Web:** https://chord-shed.vercel.app · **Android:** [download the APK](../../releases/latest)

---

## What it does

- **Both instruments, six tunings.** Ukulele standard (re-entrant gCEA), low-G and baritone; guitar
  standard, drop D and open G. Changing tuning regenerates every shape.
- **Voicings are computed, not looked up.** For each chord it searches the fretboard in the current
  tuning, keeps only shapes where every sounding string is a chord tone and the root is present,
  discards anything with a stretch over four frets or more than four fingers, then ranks what is
  left by position, open strings, reach and bass note. Up to eight voicings spread across the neck.
  The handful of shapes players actually reach for first (uke C, guitar F barre, …) are hand-set and
  validated against the chord before being shown first.
- **Fingering is derived per shape** — finger numbers in the dots, barres detected and drawn.
- **Sound** is a Karplus–Strong plucked string synthesized in the browser: shorter and brighter for
  nylon uke than for guitar. Strum a shape, or tap one string to hear it alone.
- 18 chord qualities, a chord-name parser (`F#m7`, `Bbsus4`), interval-correct note spelling
  (C7 shows B♭, not A♯), a left-handed mirror, a chord chart, and a chords-in-a-key helper.

Missing chord tones are reported rather than hidden — on four strings a ninth chord has to drop its
fifth, and the tone chip greys out so you can see which one went.

## Layout

```
index.html              the whole web app — no build step, no dependencies
android/                WebView wrapper that ships index.html as an offline asset
  AndroidManifest.xml
  build.ps1             aapt2 -> javac -> d8 -> zipalign -> apksigner
  mkhtml.py             turns ../index.html into a standalone offline page
  fetchfonts.py         re-downloads the bundled font subsets
  assets/*.woff2        Bodoni Moda + IBM Plex subsets (SIL Open Font License)
  res/, src/
```

## The web app

`index.html` is self-contained. Open it in a browser, or serve the repo root as a static site —
that is all Vercel does with it. Fonts come from Google Fonts over the network.

## Building the APK

Needs a JDK and an Android SDK with `build-tools` and a platform installed. No Gradle, no Android
Studio, no network.

```powershell
cd android
.\build.ps1
```

Output: `android/out/chord-shed.apk`. Package `com.chordshed.app`, minSdk 26 (Android 8.0),
targetSdk 34, ~522 KB.

The APK **declares no permissions at all** — not even `INTERNET`. The page and its three typefaces
ship inside the package, so it works in airplane mode and cannot phone home.

### Signing

`build.ps1` generates `chordshed.keystore` on first run if it is missing. That file is
**deliberately not in this repo** — anyone holding it could sign an update that an installed phone
accepts as the same app. Keep your copy if you want to ship updates that install over an existing
copy; lose it and you have to uninstall first.

### A Windows gotcha, if you fork this

`aapt2` on Windows packs *nested* asset directories using a backslash — `assets/fonts\file.woff2` —
which `file:///android_asset/fonts/file.woff2` then fails to resolve, silently and with no error
anywhere. That is why `android/assets/` is flat. `build.ps1` verifies every `url()` in the bundled
CSS resolves to a real entry.

## Licence

App code: do what you like with it. The bundled fonts are Bodoni Moda and IBM Plex, both under the
SIL Open Font License.
