$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# Rasterize the shapes from brand/logo.svg using Windows GDI+.
# iOS adds its own rounded corners, so the icon uses an opaque square backdrop.
$assetsPath = Join-Path $PSScriptRoot '..\Waslora\Assets.xcassets'
$iconPath = Join-Path $assetsPath 'AppIcon.appiconset'
$brandPath = Join-Path $assetsPath 'BrandMark.imageset'
New-Item -ItemType Directory -Path $iconPath, $brandPath -Force | Out-Null
$bitmap = [Drawing.Bitmap]::new(1024, 1024, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([Drawing.ColorTranslator]::FromHtml('#101832'))
$graphics.ScaleTransform(2, 2)
$gradient = [Drawing.Drawing2D.LinearGradientBrush]::new(
    [Drawing.Point]::new(104, 161), [Drawing.Point]::new(405, 350),
    [Drawing.ColorTranslator]::FromHtml('#51E0D2'), [Drawing.ColorTranslator]::FromHtml('#8CA2FF'))
$gradient.WrapMode = [Drawing.Drawing2D.WrapMode]::TileFlipXY
$mainPen = [Drawing.Pen]::new($gradient, 27)
$tealPen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#51E0D2'), 27)
$bluePen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#8CA2FF'), 27)
foreach ($pen in @($mainPen, $tealPen, $bluePen)) {
    $pen.StartCap = [Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [Drawing.Drawing2D.LineJoin]::Round
}
$upperPath = [Drawing.Drawing2D.GraphicsPath]::new()
$upperPath.AddLine(112, 170, 329, 170)
$upperPath.AddBezier(329, 170, 363, 170, 384, 190, 384, 223)
$upperPath.AddLine(384, 223, 384, 245)
$lowerPath = [Drawing.Drawing2D.GraphicsPath]::new()
$lowerPath.AddLine(400, 342, 183, 342)
$lowerPath.AddBezier(183, 342, 149, 342, 128, 322, 128, 289)
$lowerPath.AddLine(128, 289, 128, 267)
$playBrush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#FFD078'))
try {
    $graphics.DrawPath($mainPen, $upperPath)
    $graphics.DrawLines($tealPen, [Drawing.Point[]]@([Drawing.Point]::new(350, 234), [Drawing.Point]::new(384, 272), [Drawing.Point]::new(418, 234)))
    $graphics.DrawPath($mainPen, $lowerPath)
    $graphics.DrawLines($bluePen, [Drawing.Point[]]@([Drawing.Point]::new(162, 278), [Drawing.Point]::new(128, 240), [Drawing.Point]::new(94, 278)))
    $graphics.FillPolygon($playBrush, [Drawing.Point[]]@([Drawing.Point]::new(225, 211), [Drawing.Point]::new(316, 256), [Drawing.Point]::new(225, 301)))
    foreach ($size in @(20, 29, 40, 58, 60, 76, 80, 87, 120, 152, 167, 180, 1024)) {
        $scaled = [Drawing.Bitmap]::new($size, $size, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $scaledGraphics = [Drawing.Graphics]::FromImage($scaled)
        try {
            $scaledGraphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $scaledGraphics.DrawImage($bitmap, 0, 0, $size, $size)
            $scaled.Save((Join-Path $iconPath "Icon-$size.png"), [Drawing.Imaging.ImageFormat]::Png)
        } finally { $scaledGraphics.Dispose(); $scaled.Dispose() }
    }
    $bitmap.Save((Join-Path $brandPath 'BrandMark.png'), [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $upperPath.Dispose(); $lowerPath.Dispose(); $playBrush.Dispose()
    $mainPen.Dispose(); $tealPen.Dispose(); $bluePen.Dispose(); $gradient.Dispose()
    $graphics.Dispose(); $bitmap.Dispose()
}

$images = @()
foreach ($pointSize in @(20, 29, 40, 60)) {
    foreach ($scale in @(2, 3)) {
        $pixels = $pointSize * $scale
        $images += @{ idiom = 'iphone'; size = "${pointSize}x${pointSize}"; scale = "${scale}x"; filename = "Icon-$pixels.png" }
    }
}
foreach ($pointSize in @(20, 29, 40, 76)) {
    foreach ($scale in @(1, 2)) {
        $pixels = $pointSize * $scale
        $images += @{ idiom = 'ipad'; size = "${pointSize}x${pointSize}"; scale = "${scale}x"; filename = "Icon-$pixels.png" }
    }
}
$images += @{ idiom = 'ipad'; size = '83.5x83.5'; scale = '2x'; filename = 'Icon-167.png' }
$images += @{ idiom = 'ios-marketing'; size = '1024x1024'; scale = '1x'; filename = 'Icon-1024.png' }
$info = @{ author = 'xcode'; version = 1 }
@{ info = $info } | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $assetsPath 'Contents.json') -Encoding utf8
@{ images = $images; info = $info } | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $iconPath 'Contents.json') -Encoding utf8
@{ images = @(@{ idiom = 'universal'; filename = 'BrandMark.png' }); info = $info } | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $brandPath 'Contents.json') -Encoding utf8
Write-Output 'Waslora iOS app icons generated.'
