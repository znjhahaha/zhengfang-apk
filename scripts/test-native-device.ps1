param([Parameter(Mandatory)][string]$Serial,[Parameter(Mandatory)][int]$Api,[string]$AdbPath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'android-adb.ps1')
$adb=Resolve-AndroidAdb -AdbPath $AdbPath
$root=Split-Path -Parent $PSScriptRoot
$actual=(& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
if($actual -ne [string]$Api){throw 'Unexpected Android API level'}
& $adb -s $Serial install -r (Join-Path $root 'app/build/outputs/apk/uiPreview/app-uiPreview.apk')
if($LASTEXITCODE -ne 0){throw 'Preview APK installation failed'}
& $adb -s $Serial install -r (Join-Path $root 'app/build/outputs/apk/androidTest/uiPreview/app-uiPreview-androidTest.apk')
if($LASTEXITCODE -ne 0){throw 'Test APK installation failed'}
if($Api -ge 33){
    & $adb -s $Serial shell pm grant com.tyust.course.uipreview android.permission.POST_NOTIFICATIONS
    if($LASTEXITCODE -ne 0){throw 'Preview notification permission setup failed'}
}
$classes='com.tyust.course.academic.plugin.NativePluginDeviceTest,com.tyust.course.academic.plugin.NativePluginUiDeviceTest,com.tyust.course.academic.plugin.NativeServiceDeviceTest,com.tyust.course.academic.plugin.CampusServiceDeviceTest,com.tyust.course.academic.plugin.CampusServiceUiDeviceTest,com.tyust.course.academic.plugin.PluginSandboxDeviceTest,com.tyust.course.academic.plugin.PluginCenterDeviceTest'
$log=Join-Path $root ('.local/native-api'+$Api+'.log')
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $log) | Out-Null
& $adb -s $Serial shell am instrument -w -r -e class $classes com.tyust.course.uipreview.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath $log
$text=Get-Content -LiteralPath $log -Raw
if($text -notmatch 'OK \(\d+ tests?\)' -or $text -match 'FAILURES|INSTRUMENTATION_FAILED'){throw ('Device tests did not pass: '+$log)}
Write-Output ('API '+$Api+' native and legacy plugin device tests passed.')
