# Build Chord Shed straight from the Android SDK build-tools: no Gradle, no network.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$SDK  = "$env:LOCALAPPDATA\Android\Sdk"
$BT   = "$SDK\build-tools\35.0.0"
$JAR  = "$SDK\platforms\android-35\android.jar"
$AAPT = "$BT\aapt2.exe"
$D8   = "$BT\d8.bat"
$ALIGN= "$BT\zipalign.exe"
$SIGN = "$BT\apksigner.bat"

foreach ($p in $AAPT, $D8, $ALIGN, $SIGN, $JAR) {
  if (-not (Test-Path $p)) { throw "missing build tool: $p" }
}

# versionCode must strictly increase or Android refuses the update.
$VERSION_CODE = 6
$VERSION_NAME = '1.5'
$OUT = 'out'

if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$OUT\flat","$OUT\gen","$OUT\classes","$OUT\dex" | Out-Null

# 0. regenerate assets/index.html from the web page at the repo root
Write-Host '-> mkhtml (bundle offline page)' -ForegroundColor Cyan
& python mkhtml.py
if ($LASTEXITCODE -ne 0) { throw 'mkhtml.py failed' }

# 0b. A syntax error in the page's one inline script fails silently: the browser
#     renders the static HTML and runs nothing, so every button is dead. Catch it here.
Write-Host '-> syntax check' -ForegroundColor Cyan
& python ..\check.py ..\index.html assets\index.html
if ($LASTEXITCODE -ne 0) { throw 'inline script failed syntax check' }

function Run($exe, $argList, $what) {
  Write-Host "-> $what" -ForegroundColor Cyan
  & $exe @argList 2>&1 | ForEach-Object { "   $_" }
  if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

# 1. compile resources
Run $AAPT @('compile','--dir','res','-o',"$OUT\flat\res.zip") 'aapt2 compile'

# 2. link resources + manifest + assets into a base APK, and emit R.java
Run $AAPT @(
  'link','-o',"$OUT\base.apk",
  '-I',$JAR,
  '--manifest','AndroidManifest.xml',
  '-R',"$OUT\flat\res.zip",
  '-A','assets',
  '--java',"$OUT\gen",
  '--min-sdk-version','26',
  '--target-sdk-version','34',
  '--version-code',"$VERSION_CODE",
  '--version-name',$VERSION_NAME,
  '--no-version-vectors',
  '--auto-add-overlay'
) 'aapt2 link'

# 3. compile Java
$sources = @(Get-ChildItem -Recurse -Filter *.java -Path src,"$OUT\gen" | ForEach-Object FullName)
Write-Host "-> javac ($($sources.Count) sources)" -ForegroundColor Cyan
# javac writes notes/warnings to stderr; capture to a file so a non-fatal note
# is not promoted to a terminating error under $ErrorActionPreference = 'Stop'.
$log = "$OUT\javac.log"
$ec = (Start-Process -FilePath 'javac' -NoNewWindow -Wait -PassThru `
        -ArgumentList (@('-nowarn','-source','11','-target','11','-encoding','UTF-8',
                         '-classpath',$JAR,'-d',"$OUT\classes") + $sources) `
        -RedirectStandardError $log -RedirectStandardOutput "$OUT\javac.out").ExitCode
Get-Content $log -ErrorAction SilentlyContinue |
  Where-Object { $_ -notmatch 'bootstrap class path|source value|target value|^\s*$' } |
  ForEach-Object { "   $_" }
if ($ec -ne 0) { throw "javac failed (exit $ec)" }

# 4. dex
$classes = @(Get-ChildItem -Recurse -Filter *.class -Path "$OUT\classes" | ForEach-Object FullName)
Run $D8 (@('--lib',$JAR,'--min-api','26','--release','--output',"$OUT\dex") + $classes) 'd8'

# 5. fold classes.dex into the APK
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item "$OUT\base.apk" "$OUT\unsigned.apk" -Force
$zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path "$OUT\unsigned.apk"), 'Update')
try {
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Resolve-Path "$OUT\dex\classes.dex").Path, 'classes.dex',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }
Write-Host '-> classes.dex added' -ForegroundColor Cyan

# 6. align (must precede signing: apksigner preserves alignment, zipalign would break the signature)
Run $ALIGN @('-p','-f','4',"$OUT\unsigned.apk","$OUT\aligned.apk") 'zipalign'

# 7. sign
$KS = 'chordshed.keystore'
if (-not (Test-Path $KS)) {
  Write-Host '-> generating signing key (valid 30 years)' -ForegroundColor Cyan
  & keytool -genkeypair -v -keystore $KS -alias chordshed -keyalg RSA -keysize 2048 `
      -validity 10950 -storepass chordshed -keypass chordshed `
      -dname 'CN=Chord Shed, OU=Local Build, O=Chord Shed, C=US' 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
Run $SIGN @(
  'sign','--ks',$KS,'--ks-pass','pass:chordshed','--key-pass','pass:chordshed',
  '--ks-key-alias','chordshed',
  '--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true',
  '--out',"$OUT\chord-shed.apk","$OUT\aligned.apk"
) 'apksigner sign'

Run $SIGN @('verify','--verbose',"$OUT\chord-shed.apk") 'apksigner verify'

# 8. sanity-check the packaged assets.
#    aapt2 on Windows packs NESTED asset dirs with a backslash ("assets/fonts\x.woff2"),
#    which file:///android_asset/fonts/x.woff2 will not resolve -- silently, with no error
#    anywhere. Keeping assets/ flat avoids it; this proves it stayed that way.
Write-Host '-> verify asset paths' -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path "$OUT\chord-shed.apk"))
try {
  $names = $zip.Entries | ForEach-Object { $_.FullName }

  $backslashed = @($names | Where-Object { $_ -match '\\' })
  if ($backslashed.Count) { throw "APK entries contain a backslash: $($backslashed -join ', ')" }

  $cssEntry = $zip.Entries | Where-Object { $_.FullName -eq 'assets/fonts.css' }
  if (-not $cssEntry) { throw 'assets/fonts.css missing from the APK' }
  $css = [System.IO.StreamReader]::new($cssEntry.Open()).ReadToEnd()

  $urls = [regex]::Matches($css, 'url\(([^)]+)\)') | ForEach-Object { $_.Groups[1].Value.Trim("'`"") }
  $missing = @($urls | Where-Object { $names -notcontains "assets/$_" })
  if ($missing.Count) { throw "font files referenced but not packaged: $($missing -join ', ')" }

  foreach ($need in 'assets/index.html','classes.dex','resources.arsc') {
    if ($names -notcontains $need) { throw "APK is missing $need" }
  }
  "   $($urls.Count) font references resolved, $($names.Count) entries, no backslash paths"
} finally { $zip.Dispose() }

$size = (Get-Item "$OUT\chord-shed.apk").Length
Write-Host ''
Write-Host ("BUILT  out\chord-shed.apk  ({0:N0} KB)" -f ($size/1KB)) -ForegroundColor Green

# 9. publish the version the site's update check reads
$stamp = Get-Date -Format 'yyyy-MM-dd'
$verJson = @{
  versionName = $VERSION_NAME
  versionCode = $VERSION_CODE
  apk         = '/dl/chord-shed.apk'
  released    = $stamp
  notes       = 'See the release notes on GitHub for what changed.'
} | ConvertTo-Json
Set-Content -Path '../version.json' -Value $verJson -Encoding UTF8
Write-Host "-> wrote version.json ($VERSION_NAME / $VERSION_CODE)" -ForegroundColor Cyan
