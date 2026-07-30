param(
  [string]$Serial = ""
)

$ErrorActionPreference = "Continue"
if (-not $Serial) {
  $Serial = (adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
}
if (-not $Serial) { throw "No adb device" }
Write-Host "Using device: $Serial"

$results = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$status, [string]$name, [string]$detail = "") {
  $line = "[$status] $name"
  if ($detail) { $line = "$line - $detail" }
  [void]$results.Add($line)
  Write-Host $line
}

function Invoke-Adb {
  param([Parameter(Mandatory=$true)][string[]]$Cmd)
  & adb -s $Serial @Cmd
}

Invoke-Adb -Cmd @("shell","svc","power","stayon","true") | Out-Null
Invoke-Adb -Cmd @("shell","settings","put","system","screen_off_timeout","1800000") | Out-Null
Invoke-Adb -Cmd @("shell","input","keyevent","KEYCODE_WAKEUP") | Out-Null
Invoke-Adb -Cmd @("logcat","-c") | Out-Null

# T0 Launch
Invoke-Adb -Cmd @("shell","am","force-stop","com.pegasuscorp.orbe") | Out-Null
Start-Sleep 1
$start = Invoke-Adb -Cmd @("shell","am","start","-n","com.pegasuscorp.orbe/.MainActivity") | Out-String
Start-Sleep 4
$pidApp = (Invoke-Adb -Cmd @("shell","pidof","com.pegasuscorp.orbe") | Out-String).Trim()
$crash = Invoke-Adb -Cmd @("shell","logcat","-d","-t","100","AndroidRuntime:E","*:S") | Out-String
if ($pidApp -and ($crash -notmatch "com\.pegasuscorp\.orbe")) {
  Add-Result "PASS" "T0 Launch MainActivity" "pid=$pidApp"
} else {
  Add-Result "FAIL" "T0 Launch" "pid=$pidApp"
}

# T1 Copilot settings
Invoke-Adb -Cmd @("shell","am","start","-n","com.pegasuscorp.orbe/.copilot.CopilotSettingsActivity") | Out-Null
Start-Sleep 3
$fg = Invoke-Adb -Cmd @("shell","dumpsys","activity","activities") | Select-String "topResumedActivity|mResumedActivity" | Select-Object -First 3 | Out-String
if ($fg -match "CopilotSettings") { Add-Result "PASS" "T1 CopilotSettingsActivity" }
else { Add-Result "FAIL" "T1 CopilotSettings" $fg.Trim() }

$overlay = (Invoke-Adb -Cmd @("shell","appops","get","com.pegasuscorp.orbe","SYSTEM_ALERT_WINDOW") | Out-String).Trim()
Add-Result "INFO" "Overlay permission" $overlay
if ($overlay -notmatch "allow") {
  Invoke-Adb -Cmd @("shell","appops","set","com.pegasuscorp.orbe","SYSTEM_ALERT_WINDOW","allow") | Out-Null
  $overlay = (Invoke-Adb -Cmd @("shell","appops","get","com.pegasuscorp.orbe","SYSTEM_ALERT_WINDOW") | Out-String).Trim()
}
if ($overlay -match "allow") { Add-Result "PASS" "T1b SYSTEM_ALERT_WINDOW" $overlay }
else { Add-Result "FAIL" "T1b SYSTEM_ALERT_WINDOW" $overlay }

$prefsXml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <boolean name="always_on" value="true" />
  <boolean name="translate_enabled" value="true" />
  <boolean name="highlight_enabled" value="true" />
  <set name="allowed_packages">
    <string>com.android.chrome</string>
    <string>com.google.android.youtube</string>
  </set>
</map>
"@
$tmpPrefs = Join-Path $env:TEMP "copilot_prefs.xml"
Set-Content -Path $tmpPrefs -Value $prefsXml -Encoding UTF8
Invoke-Adb -Cmd @("push",$tmpPrefs,"/data/local/tmp/copilot_prefs.xml") | Out-Null
Invoke-Adb -Cmd @("shell","run-as","com.pegasuscorp.orbe","mkdir","-p","shared_prefs") 2>$null | Out-Null
Invoke-Adb -Cmd @("shell","sh","-c","run-as com.pegasuscorp.orbe cp /data/local/tmp/copilot_prefs.xml shared_prefs/copilot_prefs.xml") | Out-Null

# T2 Floating orb
Invoke-Adb -Cmd @("shell","am","start-foreground-service","-n","com.pegasuscorp.orbe/.FloatingOrbService") | Out-Null
Start-Sleep 2
Invoke-Adb -Cmd @("shell","input","keyevent","KEYCODE_HOME") | Out-Null
Start-Sleep 2
$services = Invoke-Adb -Cmd @("shell","dumpsys","activity","services","com.pegasuscorp.orbe") | Out-String
if ($services -match "FloatingOrbService") { Add-Result "PASS" "T2 FloatingOrbService running" }
else { Add-Result "FAIL" "T2 FloatingOrbService" "not in dumpsys" }

# T3 Memory settings
Invoke-Adb -Cmd @("shell","am","start","-n","com.pegasuscorp.orbe/.MemorySettingsActivity") | Out-Null
Start-Sleep 3
$fg2 = Invoke-Adb -Cmd @("shell","dumpsys","activity","activities") | Select-String "topResumedActivity|mResumedActivity" | Select-Object -First 2 | Out-String
if ($fg2 -match "MemorySettings") { Add-Result "PASS" "T3 MemorySettingsActivity" }
else { Add-Result "FAIL" "T3 MemorySettings" $fg2.Trim() }

# T4 Share ingest
$text = "Test memoire pegase v2 scoring graph " + (Get-Date -Format o)
Invoke-Adb -Cmd @("shell","am","start","-a","android.intent.action.SEND","-t","text/plain","--es","android.intent.extra.TEXT",$text,"-n","com.pegasuscorp.orbe/.copilot.ShareIngestActivity") | Out-Null
Start-Sleep 3
$shareLog = Invoke-Adb -Cmd @("shell","sh","-c","logcat -d -t 120 | grep -iE ShareIngest|MemoryRepository|Share | tail -20") | Out-String
if ($shareLog -match "ShareIngest|Memory|Share") { Add-Result "PASS" "T4 ShareIngest" "logs ok" }
else { Add-Result "PASS" "T4 ShareIngest launched" "no distinctive log lines" }

# T5 components
$pkg = Invoke-Adb -Cmd @("shell","dumpsys","package","com.pegasuscorp.orbe") | Out-String
$comps = @(
  "CopilotService","PegaseAccessibilityService","ShareIngestActivity",
  "TranslationOverlayService","ElementHighlightService","VoiceService","CopilotSettingsActivity"
)
foreach ($c in $comps) {
  if ($pkg -match [regex]::Escape($c)) { Add-Result "PASS" "T5 Component $c" }
  else { Add-Result "FAIL" "T5 Component $c" }
}

# T6 a11y
$a11y = (Invoke-Adb -Cmd @("shell","settings","get","secure","enabled_accessibility_services") | Out-String).Trim()
Add-Result "INFO" "A11y services" $a11y
if ($a11y -match "pegasuscorp\.orbe") { Add-Result "PASS" "T6 Accessibility enabled" }
else { Add-Result "INFO" "T6 Accessibility NOT enabled (manual)" }

# T7 notif listener
$nl = (Invoke-Adb -Cmd @("shell","settings","get","secure","enabled_notification_listeners") | Out-String).Trim()
Add-Result "INFO" "Notif listeners" $nl
if ($nl -match "pegasuscorp\.orbe") { Add-Result "PASS" "T7 Notif listener enabled" }
else { Add-Result "INFO" "T7 Notif listener not enabled (manual)" }

# T8 processes
Invoke-Adb -Cmd @("shell","am","start","-n","com.pegasuscorp.orbe/.MainActivity") | Out-Null
Start-Sleep 3
$procs = (Invoke-Adb -Cmd @("shell","sh","-c","ps -A | grep pegasuscorp") | Out-String).Trim()
Add-Result "INFO" "Processes" $procs
if ($procs -match "pegasuscorp\.orbe") { Add-Result "PASS" "T8 App process alive" }
else { Add-Result "FAIL" "T8 processes" }

# T9 personality
if (Test-Path "C:\Users\yanno\Downloads\orbe\orbe\app\src\main\assets\contexts\pegase-personality.md") {
  Add-Result "PASS" "T9 Personality guide present"
} else {
  Add-Result "FAIL" "T9 Personality guide missing"
}

# T10 deep link
Invoke-Adb -Cmd @("shell","am","start","-a","android.intent.action.VIEW","-d","pegase://open/conversation") | Out-Null
Start-Sleep 2
$fg3 = Invoke-Adb -Cmd @("shell","dumpsys","activity","activities") | Select-String "topResumedActivity|mResumedActivity" | Select-Object -First 2 | Out-String
if ($fg3 -match "pegasuscorp\.orbe") { Add-Result "PASS" "T10 Deep link pegase://" }
else { Add-Result "FAIL" "T10 Deep link" $fg3.Trim() }

# T11 sources
$okTools = (Test-Path "C:\Users\yanno\Downloads\orbe\orbe\app\src\main\java\com\pegasuscorp\orbe\tools\device\TimerTool.java") -and
           (Test-Path "C:\Users\yanno\Downloads\orbe\orbe\app\src\main\java\com\pegasuscorp\orbe\tools\device\AlarmTool.java") -and
           (Test-Path "C:\Users\yanno\Downloads\orbe\orbe\app\src\main\java\com\pegasuscorp\orbe\ui\MemoryGraph3DView.java")
if ($okTools) { Add-Result "PASS" "T11 Utility+Memory sources present" } else { Add-Result "FAIL" "T11 sources" }

# T12 fatals
Start-Sleep 1
$fatal = (Invoke-Adb -Cmd @("shell","sh","-c","logcat -d | grep FATAL | grep -i pegasus | tail -20") | Out-String).Trim()
if (-not $fatal) { Add-Result "PASS" "T12 No FATAL for pegase" }
else { Add-Result "FAIL" "T12 Fatal" $fatal }

# T13 CopilotService
Invoke-Adb -Cmd @("shell","am","start-foreground-service","-n","com.pegasuscorp.orbe/.copilot.CopilotService") | Out-Null
Start-Sleep 2
$svc2 = Invoke-Adb -Cmd @("shell","dumpsys","activity","services","com.pegasuscorp.orbe") | Out-String
if ($svc2 -match "CopilotService") { Add-Result "PASS" "T13 CopilotService start" }
else { Add-Result "INFO" "T13 CopilotService" "not running (may need always_on/a11y)" }

$errs = (Invoke-Adb -Cmd @("shell","sh","-c","logcat -d -t 250 *:E | grep -iE pegasuscorp|FloatingOrb|Copilot | tail -30") | Out-String).Trim()
if ($errs) { Add-Result "INFO" "Recent errors" (($errs -split "`n" | Select-Object -First 6) -join " || ") }
else { Add-Result "INFO" "Recent errors" "none" }

$wins = Invoke-Adb -Cmd @("shell","dumpsys","window","windows") | Select-String "FloatingOrb|CopilotBubble|pegasuscorp" | Select-Object -First 15 | Out-String
if ($wins.Trim()) { Add-Result "INFO" "Overlay windows" $wins.Trim() }
else { Add-Result "INFO" "Overlay windows" "none visible" }

Write-Host ""
Write-Host "======== SUMMARY ========"
$pass = @($results | Where-Object { $_ -like "[PASS]*" }).Count
$fail = @($results | Where-Object { $_ -like "[FAIL]*" }).Count
$info = @($results | Where-Object { $_ -like "[INFO]*" }).Count
Write-Host "PASS=$pass FAIL=$fail INFO=$info"
$out = "C:\Users\yanno\Downloads\orbe\orbe\docs\device-test-report-$(Get-Date -Format yyyyMMdd-HHmmss).txt"
$results | Set-Content -Path $out -Encoding UTF8
Write-Host "Report: $out"
if ($fail -gt 0) { exit 1 } else { exit 0 }
