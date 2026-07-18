# Dev helper: OS-level screen capture (CopyFromScreen). Unlike CDP's
# Page.captureScreenshot, this sees mpv's video layer — the only way to
# verify subtitle *rendering* (fonts, styling) hands-off on Windows.
param([Parameter(Mandatory = $true)][string]$OutFile)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

$bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bitmap = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
$bitmap.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()
Write-Output "saved $OutFile"
