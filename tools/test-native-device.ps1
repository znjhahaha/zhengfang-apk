param([Parameter(Mandatory)][string]$Serial,[Parameter(Mandatory)][int]$Api)
$ErrorActionPreference='Stop'
$adb='C:\Users\82206\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$root=Split-Path -Parent $PSScriptRoot
$actual=(& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
if($actual -ne [string]$Api){throw 'Unexpected Android API level'}
& $adb -s $Serial install -r (Join-Path $root 'app/build/outputs/apk/uiPreview/app-uiPreview.apk')
if($LASTEXITCODE -ne 0){throw 'Preview APK installation failed'}
& $adb -s $Serial install -r (Join-Path $root 'app/build/outputs/apk/androidTest/uiPreview/app-uiPreview-androidTest.apk')
if($LASTEXITCODE -ne 0){throw 'Test APK installation failed'}
$classes='com.tyust.course.academic.plugin.NativePluginDeviceTest,com.tyust.course.academic.plugin.NativePluginUiDeviceTest,com.tyust.course.academic.plugin.CampusServiceDeviceTest,com.tyust.course.academic.plugin.PluginSandboxDeviceTest'
$log=Join-Path $root ('.local/native-api'+$Api+'.log')
& $adb -s $Serial shell am instrument -w -r -e class $classes com.tyust.course.uipreview.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath $log
$text=Get-Content -LiteralPath $log -Raw
if($text -notmatch 'OK \(\d+ tests?\)' -or $text -match 'FAILURES|INSTRUMENTATION_FAILED'){throw ('Device tests did not pass: '+$log)}
Write-Output ('API '+$Api+' native and legacy plugin device tests passed.')
