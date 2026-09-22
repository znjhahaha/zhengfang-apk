param([Parameter(Mandatory)][string]$Serial,[Parameter(Mandatory)][int]$Api,[switch]$BindingsOnly,[string]$AdbPath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'android-adb.ps1')
$taskAdb=Resolve-AndroidAdb -AdbPath $AdbPath
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskActual=(& $taskAdb -s $Serial shell getprop ro.build.version.sdk).Trim()
if($taskActual -ne [string]$Api){throw 'Unexpected Android API level'}
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'app/build/outputs/apk/uiPreview/app-uiPreview.apk')
if($LASTEXITCODE -ne 0){throw 'Preview APK installation failed'}
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'app/build/outputs/apk/androidTest/uiPreview/app-uiPreview-androidTest.apk')
if($LASTEXITCODE -ne 0){throw 'Test APK installation failed'}
$taskClasses='com.tyust.course.academic.plugin.PluginSchoolBindingDeviceTest,com.tyust.course.ui.SurveyReminderDeviceTest,com.tyust.course.ui.ScheduleAdaptationDeviceTest#headerUsesParentWidthAndWeekdaysStayAlignedWithEveryCourseColumn,com.tyust.course.ui.ScheduleAdaptationDeviceTest#collapsedActionsRemainReachableAndControlsRestoreAfterReversingDrag'
$taskExpected=9
$taskLabel='school bindings, survey reminders and schedule header'
$taskPrefix='final-regression'
if($BindingsOnly){
  $taskClasses='com.tyust.course.academic.plugin.PluginSchoolBindingDeviceTest'
  $taskExpected=4
  $taskLabel='signed school bindings'
  $taskPrefix='school-bindings'
}
$taskLog=Join-Path $taskRoot ('.local/'+$taskPrefix+'-api'+$Api+'.log')
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $taskLog) | Out-Null
& $taskAdb -s $Serial shell am instrument -w -r -e class $taskClasses com.tyust.course.uipreview.test/androidx.test.runner.AndroidJUnitRunner > $taskLog
$taskText=Get-Content -LiteralPath $taskLog -Raw
if($taskText -notmatch ('OK \('+$taskExpected+' tests\)') -or $taskText -match 'FAILURES|INSTRUMENTATION_FAILED'){
  Get-Content -LiteralPath $taskLog -Tail 100
  throw ('Device regression failed: '+$taskLog)
}
Write-Output ('API '+$Api+': '+$taskLabel+' passed ('+$taskExpected+' tests).')
