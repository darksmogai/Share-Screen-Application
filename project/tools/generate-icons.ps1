# Generates the ScreenGuard icons: PNG (16/32/64/256) for the JavaFX window and tray icons,
# plus a real 32x32 32bpp BMP-based .ico for jpackage/WiX.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$iconDir = Join-Path $root 'src\main\resources\icons'
New-Item -ItemType Directory -Force -Path $iconDir | Out-Null

function New-ShieldBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $s = $size / 256.0

        $blue = [System.Drawing.Color]::FromArgb(255, 31, 111, 235)
        $darker = [System.Drawing.Color]::FromArgb(255, 21, 76, 165)
        $white = [System.Drawing.Color]::White

        $fill = New-Object System.Drawing.SolidBrush($blue)
        $outline = New-Object System.Drawing.Pen($darker, (7 * $s))
        $outline.Alignment = [System.Drawing.Drawing2D.PenAlignment]::Inset

        # Shield shape
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddPolygon(@(
            (New-Object System.Drawing.PointF([single](128 * $s), [single](16 * $s))),
            (New-Object System.Drawing.PointF([single](224 * $s), [single](54 * $s))),
            (New-Object System.Drawing.PointF([single](222 * $s), [single](140 * $s))),
            (New-Object System.Drawing.PointF([single](128 * $s), [single](242 * $s))),
            (New-Object System.Drawing.PointF([single](34 * $s), [single](140 * $s))),
            (New-Object System.Drawing.PointF([single](32 * $s), [single](54 * $s)))
        ))
        $g.FillPath($fill, $path)
        $g.DrawPath($outline, $path)

        # Padlock body + shackle
        $whiteBrush = New-Object System.Drawing.SolidBrush($white)
        $whitePen = New-Object System.Drawing.Pen($white, (18 * $s))
        $whitePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $whitePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

        $bodyW = 66 * $s
        $bodyH = 54 * $s
        $bodyX = 128 * $s - $bodyW / 2
        $bodyY = 128 * $s - $bodyH / 2 + 16 * $s
        $g.FillRectangle($whiteBrush, [single]$bodyX, [single]$bodyY, [single]$bodyW, [single]$bodyH)

        $r = 26 * $s
        $cx = 128 * $s
        $cy = $bodyY - 2 * $s
        $g.DrawArc($whitePen, [single]($cx - $r), [single]($cy - $r), [single](2 * $r), [single](2 * $r), 180, 180)

        # Keyhole dot
        $keyhole = New-Object System.Drawing.SolidBrush($blue)
        $g.FillEllipse($keyhole, [single]($cx - 9 * $s), [single]($bodyY + 16 * $s), [single](18 * $s), [single](18 * $s))
        return $bmp
    }
    finally {
        $g.Dispose()
    }
}

foreach ($size in 16, 32, 64, 256) {
    $bmp = New-ShieldBitmap $size
    $file = Join-Path $iconDir ("screenguard-{0}.png" -f $size)
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $file"
}

# ---- 32x32 32bpp BMP-based ICO ----
function ConvertTo-IcoBytes([System.Drawing.Bitmap]$bmp) {
    $size = $bmp.Width
    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $bits = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $rowBytes = $size * 4
        $xor = New-Object byte[] ($rowBytes * $size)
        $managed = New-Object byte[] ($bits.Stride * $size)
        [System.Runtime.InteropServices.Marshal]::Copy($bits.Scan0, $managed, 0, $managed.Length)
        for ($y = 0; $y -lt $size; $y++) {
            $srcY = $size - 1 - $y               # ICO bitmaps are bottom-up
            for ($x = 0; $x -lt $size; $x++) {
                $i = ($srcY * $bits.Stride) + ($x * 4)
                $o = ($y * $rowBytes) + ($x * 4)
                $xor[$o]     = $managed[$i + 2]   # B
                $xor[$o + 1] = $managed[$i + 1]   # G
                $xor[$o + 2] = $managed[$i]       # R
                $xor[$o + 3] = $managed[$i + 3]   # A (premultiplied by the format)
            }
        }
    }
    finally {
        $bmp.UnlockBits($bits)
    }

    $andRowBytes = 4                              # 32 bits padded to 4 bytes
    $and = New-Object byte[] ($andRowBytes * $size)

    $headerSize = 40
    $imageSize = $headerSize + $xor.Length + $and.Length

    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([uint16]0)                          # reserved
    $bw.Write([uint16]1)                          # type: icon
    $bw.Write([uint16]1)                          # image count
    $bw.Write([byte]$size)                        # width
    $bw.Write([byte]$size)                        # height
    $bw.Write([byte]0)                            # palette colours
    $bw.Write([byte]0)                            # reserved
    $bw.Write([uint16]1)                          # planes
    $bw.Write([uint16]32)                         # bits per pixel
    $bw.Write([uint32]$imageSize)                 # size of the image data
    $bw.Write([uint32]22)                         # offset of the image data

    $bw.Write([uint32]$headerSize)
    $bw.Write([int32]$size)
    $bw.Write([int32]($size * 2))                 # XOR + AND height
    $bw.Write([uint16]1)
    $bw.Write([uint16]32)
    $bw.Write([uint32]0)                          # BI_RGB
    $bw.Write([uint32]($xor.Length + $and.Length))
    $bw.Write([int32]0)
    $bw.Write([int32]0)
    $bw.Write([uint32]0)
    $bw.Write([uint32]0)
    $bw.Write($xor)
    $bw.Write($and)
    $bw.Flush()
    return $ms.ToArray()
}

$icoBmp = New-ShieldBitmap 32
$icoFile = Join-Path $iconDir 'screenguard.ico'
[System.IO.File]::WriteAllBytes($icoFile, (ConvertTo-IcoBytes $icoBmp))
$icoBmp.Dispose()
Write-Output "wrote $icoFile"
