$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AndroidPlatformVersion = if ($env:WASLORA_ANDROID_PLATFORM) { $env:WASLORA_ANDROID_PLATFORM } else { '35' }
$SdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
if ($env:LOCALAPPDATA) { $SdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
$SdkCandidates += 'C:\Users\EL MOSTAWRED MF\AppData\Local\Android\Sdk'
$AndroidSdk = $SdkCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ "platforms\android-$AndroidPlatformVersion\android.jar"))
} | Select-Object -First 1
if (-not $AndroidSdk) { throw "Android SDK Platform $AndroidPlatformVersion was not found. Set ANDROID_SDK_ROOT to your Android SDK folder." }
$AndroidPlatform = Join-Path $AndroidSdk "platforms\android-$AndroidPlatformVersion\android.jar"
$JdkCandidates = @()
if ($env:JAVA_HOME) { $JdkCandidates += $env:JAVA_HOME }
$BuildCache = Join-Path $ProjectRoot '.build-cache'
if (Test-Path -LiteralPath $BuildCache) {
    $JdkCandidates += Get-ChildItem -LiteralPath $BuildCache -Directory | Select-Object -ExpandProperty FullName
}
$JdkHome = $JdkCandidates | Where-Object {
    (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'lib\modules'))
} | Select-Object -First 1
if (-not $JdkHome) { throw 'A complete JDK is required; run build.ps1 first to use the cached JDK.' }
$JdkBin = Join-Path $JdkHome 'bin'
$TestOutput = Join-Path $PSScriptRoot 'out'
$ResolvedOutput = [System.IO.Path]::GetFullPath($TestOutput)
$ResolvedTests = [System.IO.Path]::GetFullPath($PSScriptRoot)
if (-not $ResolvedOutput.StartsWith($ResolvedTests + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to write test output outside the tests folder.'
}
if (Test-Path -LiteralPath $TestOutput) { Remove-Item -LiteralPath $TestOutput -Recurse -Force }
New-Item -ItemType Directory -Path $TestOutput -Force | Out-Null
$Sources = @(
    (Join-Path $ProjectRoot 'app\src\main\java\com\waslora\app\PcmSamples.java'),
    (Join-Path $ProjectRoot 'app\src\main\java\com\waslora\app\HttpUploadBody.java'),
    (Join-Path $ProjectRoot 'app\src\main\java\com\waslora\app\DirectDownloader.java'),
    (Join-Path $ProjectRoot 'app\src\main\java\com\waslora\app\MediaMimeTypes.java'),
    (Join-Path $ProjectRoot 'app\src\main\java\com\waslora\app\PlatformDownloadQuality.java'),
    (Join-Path $PSScriptRoot 'PcmSamplesTest.java'),
    (Join-Path $PSScriptRoot 'HttpUploadBodyTest.java'),
    (Join-Path $PSScriptRoot 'DirectDownloaderLinkTest.java'),
    (Join-Path $PSScriptRoot 'MediaMimeTypesTest.java'),
    (Join-Path $PSScriptRoot 'PlatformDownloadQualityTest.java')
)
& (Join-Path $JdkBin 'javac.exe') -encoding UTF-8 -classpath $AndroidPlatform -d $TestOutput @Sources
if ($LASTEXITCODE -ne 0) { throw 'Java regression tests did not compile.' }
$RuntimeClasspath = $TestOutput + ';' + $AndroidPlatform
& (Join-Path $JdkBin 'java.exe') -classpath $RuntimeClasspath com.waslora.app.PcmSamplesTest
if ($LASTEXITCODE -ne 0) { throw 'PCM conversion regression tests failed.' }
& (Join-Path $JdkBin 'java.exe') -classpath $RuntimeClasspath com.waslora.app.HttpUploadBodyTest
if ($LASTEXITCODE -ne 0) { throw 'Local-transfer upload tests failed.' }
& (Join-Path $JdkBin 'java.exe') -classpath $RuntimeClasspath com.waslora.app.DirectDownloaderLinkTest
if ($LASTEXITCODE -ne 0) { throw 'Direct-link validation regression tests failed.' }
& (Join-Path $JdkBin 'java.exe') -classpath $RuntimeClasspath com.waslora.app.MediaMimeTypesTest
if ($LASTEXITCODE -ne 0) { throw 'Media MIME type regression tests failed.' }
& (Join-Path $JdkBin 'java.exe') -classpath $RuntimeClasspath com.waslora.app.PlatformDownloadQualityTest
if ($LASTEXITCODE -ne 0) { throw 'Platform download quality tests failed.' }
node (Join-Path $PSScriptRoot 'mp3-worker-smoke.cjs')
if ($LASTEXITCODE -ne 0) { throw 'UI and MP3 worker smoke test failed.' }
node --check (Join-Path $ProjectRoot 'app\src\main\assets\app.js')
if ($LASTEXITCODE -ne 0) { throw 'app.js syntax check failed.' }
node --check (Join-Path $ProjectRoot 'app\src\main\assets\mp3-worker.js')
if ($LASTEXITCODE -ne 0) { throw 'mp3-worker.js syntax check failed.' }
node (Join-Path $ProjectRoot 'resolver\resolver-smoke.cjs')
if ($LASTEXITCODE -ne 0) { throw 'Resolver service smoke test failed.' }
