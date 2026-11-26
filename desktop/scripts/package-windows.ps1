# Build a Windows app-image (and an exe installer when WiX is installed).
# Run from PowerShell on a Windows machine with JDK 21 and Maven.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
mvn -DskipTests package
$layout = Join-Path (Get-Location) "target\nfd-desktop"
if (-not (Test-Path (Join-Path $layout "nfd-desktop.jar"))) {
    throw "Package layout was not created: $layout"
}
java -cp "$layout\nfd-desktop.jar;$layout\lib\*" cn.qaiu.nfd.desktop.Check
$dest = Join-Path (Get-Location) "target\dist"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$common = @(
    "--name", "NFDClient",
    "--dest", $dest,
    "--input", $layout,
    "--main-jar", "nfd-desktop.jar",
    "--main-class", "cn.qaiu.nfd.desktop.DesktopApp",
    "--app-version", "0.1.0",
    "--vendor", "qaiu",
    "--module-path", (Join-Path $layout "javafx"),
    "--add-modules", "javafx.controls"
)
& jpackage @common --type app-image
if (Get-Command candle -ErrorAction SilentlyContinue) {
    & jpackage @common --type exe --win-shortcut
    Write-Host "Installer: $dest\NFDClient-0.1.0.exe"
} else {
    Write-Host "WiX is not installed, so only the app-image was built: $dest\NFDClient"
}
