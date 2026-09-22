function Resolve-AndroidAdb {
    param([string]$AdbPath)
    if ($AdbPath) {
        return (Resolve-Path -LiteralPath $AdbPath -ErrorAction Stop).Path
    }
    foreach ($sdk in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($sdk) {
            $candidate = Join-Path $sdk 'platform-tools/adb.exe'
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'Set ANDROID_HOME / ANDROID_SDK_ROOT, add adb to PATH, or pass -AdbPath.'
}
