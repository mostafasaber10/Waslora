$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidSdk = 'C:\Users\EL MOSTAWRED MF\AppData\Local\Android\Sdk'
$AndroidPlatform = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$BuildTools = Join-Path $AndroidSdk 'build-tools\36.0.0'
$StudioJdk = 'C:\Program Files\Android\Android Studio\jbr'
$JdkCandidates = @()
foreach ($candidate in @($env:JAVA_HOME, $env:ANDROID_STUDIO_JDK, $StudioJdk)) {
    if ($candidate) { $JdkCandidates += $candidate }
}
$BuildCache = Join-Path $ProjectRoot '.build-cache'
if (Test-Path -LiteralPath $BuildCache) {
    foreach ($candidate in (Get-ChildItem -LiteralPath $BuildCache -Directory | Select-Object -ExpandProperty FullName)) {
        $JdkCandidates += $candidate
    }
}
$JdkCandidates = $JdkCandidates | Select-Object -Unique
$JdkHome = $null
foreach ($candidate in $JdkCandidates) {
    if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'lib\modules'))) {
        $JdkHome = $candidate
        break
    }
}
if (-not $JdkHome) {
    throw 'A complete JDK 17 or newer is required. Set JAVA_HOME to its installation folder; the Android Studio runtime on this computer is incomplete.'
}
$JdkBin = Join-Path $JdkHome 'bin'
$Output = Join-Path $ProjectRoot 'out'
$Classes = Join-Path $Output 'classes'
$Dex = Join-Path $Output 'dex'
$Assets = Join-Path $ProjectRoot 'app\src\main\assets'
$Resources = Join-Path $ProjectRoot 'app\src\main\res'
$JniLibs = Join-Path $ProjectRoot 'app\src\main\jniLibs'
$VendorDeps = Join-Path $ProjectRoot 'vendor\youtubedl-android-0.18.1'
$YtDlpBinary = Join-Path $Resources 'raw\ytdlp'
$ExpectedYtDlpSha256 = '3f1b267b4488f3aed3731a9e84a44011ca5569901868532e10ee11fd07d69707'
$Manifest = Join-Path $ProjectRoot 'app\src\main\AndroidManifest.xml'

foreach ($required in @(
    $AndroidPlatform,
    (Join-Path $BuildTools 'aapt2.exe'),
    (Join-Path $BuildTools 'd8.bat'),
    (Join-Path $BuildTools 'zipalign.exe'),
    (Join-Path $BuildTools 'apksigner.bat'),
    (Join-Path $JdkBin 'javac.exe'),
    (Join-Path $JdkBin 'java.exe'),
    (Join-Path $JdkBin 'jar.exe'),
    (Join-Path $JdkBin 'keytool.exe')
)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing Android build tool: $required" }
}
$ActualYtDlpSha256 = (Get-FileHash -LiteralPath $YtDlpBinary -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ActualYtDlpSha256 -ne $ExpectedYtDlpSha256) { throw "Bundled yt-dlp checksum mismatch: expected $ExpectedYtDlpSha256, got $ActualYtDlpSha256." }

$ResolvedOutput = [System.IO.Path]::GetFullPath($Output)
$ResolvedRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
if (-not $ResolvedOutput.StartsWith($ResolvedRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to build outside the Waslora project directory.'
}
foreach ($generated in @('classes','dex')) {
    $generatedPath = Join-Path $Output $generated
    if (Test-Path -LiteralPath $generatedPath) { Remove-Item -LiteralPath $generatedPath -Recurse -Force }
}
foreach ($generated in @('resources.zip','classes.jar','Waslora-unsigned.apk','Waslora-aligned.apk','Waslora-debug.apk')) {
    $generatedPath = Join-Path $Output $generated
    if (Test-Path -LiteralPath $generatedPath) { Remove-Item -LiteralPath $generatedPath -Force }
}
New-Item -ItemType Directory -Path $Classes,$Dex -Force | Out-Null

$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkBin;$env:PATH"

$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$D8 = Join-Path $BuildTools 'd8.bat'
$Zipalign = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'
$Javac = Join-Path $JdkBin 'javac.exe'
$Jar = Join-Path $JdkBin 'jar.exe'
$Keytool = Join-Path $JdkBin 'keytool.exe'

$ResourceZip = Join-Path $Output 'resources.zip'
& $Aapt2 compile --dir $Resources -o $ResourceZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 resource compilation failed.' }

$GeneratedJava = Join-Path $Output 'generated-java'
if (Test-Path -LiteralPath $GeneratedJava) { Remove-Item -LiteralPath $GeneratedJava -Recurse -Force }
New-Item -ItemType Directory -Path $GeneratedJava -Force | Out-Null
$UnsignedApk = Join-Path $Output 'Waslora-unsigned.apk'
& $Aapt2 link -o $UnsignedApk -I $AndroidPlatform --manifest $Manifest -R $ResourceZip --java $GeneratedJava --extra-packages com.yausername.youtubedl_android --min-sdk-version 24 --target-sdk-version 35 --version-code 4 --version-name 0.3.0
if ($LASTEXITCODE -ne 0) { throw 'aapt2 APK linking failed.' }

$Sources = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'app\src\main\java') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
$GeneratedSources = @(Get-ChildItem -LiteralPath $GeneratedJava -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
$Sources += $GeneratedSources
if (-not $Sources) { throw 'No Java sources found.' }
$DependencyJars = @(Get-ChildItem -LiteralPath $VendorDeps -Filter '*.jar' -File -Recurse | Select-Object -ExpandProperty FullName)
if (-not $DependencyJars) { throw 'The embedded on-device downloader dependencies are missing.' }
$CompileClasspath = ($AndroidPlatform + ';' + ($DependencyJars -join ';'))
& $Javac -encoding UTF-8 -source 8 -target 8 -classpath $CompileClasspath -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw 'javac source compilation failed.' }

$ClassJar = Join-Path $Output 'classes.jar'
& $Jar --create --file $ClassJar -C $Classes .
if ($LASTEXITCODE -ne 0) { throw 'Could not package compiled classes.' }
& $D8 --min-api 24 --lib $AndroidPlatform --output $Dex $ClassJar @DependencyJars
if ($LASTEXITCODE -ne 0) { throw 'D8 dex conversion failed.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$Archive = [System.IO.Compression.ZipFile]::Open($UnsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $DexPath = Join-Path $Dex 'classes.dex'
    if (-not (Test-Path -LiteralPath $DexPath)) { throw 'D8 did not create classes.dex.' }
    $DexEntry = $Archive.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
    $DexInput = [System.IO.File]::OpenRead($DexPath)
    $DexOutput = $DexEntry.Open()
    try { $DexInput.CopyTo($DexOutput) } finally { $DexOutput.Dispose(); $DexInput.Dispose() }

    Get-ChildItem -LiteralPath $Assets -File -Recurse | ForEach-Object {
        $Relative = $_.FullName.Substring($Assets.Length).TrimStart([char[]]@('\','/')).Replace('\','/')
        $EntryName = "assets/$Relative"
        $Entry = $Archive.CreateEntry($EntryName, [System.IO.Compression.CompressionLevel]::Optimal)
        $InputStream = [System.IO.File]::OpenRead($_.FullName)
        $OutputStream = $Entry.Open()
        try { $InputStream.CopyTo($OutputStream) } finally { $OutputStream.Dispose(); $InputStream.Dispose() }
    }
    if (Test-Path -LiteralPath $JniLibs) {
        Get-ChildItem -LiteralPath $JniLibs -File -Recurse | ForEach-Object {
            $Relative = $_.FullName.Substring($JniLibs.Length).TrimStart([char[]]@('\','/')).Replace('\','/')
            $EntryName = "lib/$Relative"
            $Entry = $Archive.CreateEntry($EntryName, [System.IO.Compression.CompressionLevel]::NoCompression)
            $InputStream = [System.IO.File]::OpenRead($_.FullName)
            $OutputStream = $Entry.Open()
            try { $InputStream.CopyTo($OutputStream) } finally { $OutputStream.Dispose(); $InputStream.Dispose() }
        }
    }
} finally { $Archive.Dispose() }

$AlignedApk = Join-Path $Output 'Waslora-aligned.apk'
$SignedApk = Join-Path $Output 'Waslora-debug.apk'
& $Zipalign -f 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }

$KeyStore = Join-Path $BuildCache 'debug.keystore'
New-Item -ItemType Directory -Path $BuildCache -Force | Out-Null
if (-not (Test-Path -LiteralPath $KeyStore)) {
    & $Keytool -genkeypair -keystore $KeyStore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Waslora Debug,O=Waslora,C=EG' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the local debug signing key.' }
}
& $ApkSigner sign --ks $KeyStore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $SignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& $ApkSigner verify --verbose $SignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

Get-Item -LiteralPath $SignedApk | Select-Object FullName,Length
