# Builds the API and the web console and collects everything to upload in <repo>\deployment.
# Run it again for every release; upload the folder (or just tms-api.jar and web-console\).
#   powershell -ExecutionPolicy Bypass -File server\deploy\package.ps1
$ErrorActionPreference = 'Stop'
$server = Split-Path $PSScriptRoot
$out = Join-Path (Split-Path $server) 'deployment'

# Runs a build tool in a folder. They write warnings to stderr, which must not stop the script.
function Invoke-Build($name, $folder, [scriptblock]$command) {
    Write-Host "Building the $name..."
    Push-Location $folder
    try {
        $ErrorActionPreference = 'Continue'
        & $command 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE) { throw "The $name build failed" }
    } finally { Pop-Location }
}
Invoke-Build 'API' "$server\backend" { & "$server\backend\mvnw.cmd" -q -DskipTests package }
Invoke-Build 'web console' "$server\web_console" { npm run build }

New-Item -ItemType Directory -Force "$out\config" | Out-Null
$jar = Get-ChildItem "$server\backend\target\*.jar" | Select-Object -First 1
Copy-Item $jar.FullName "$out\tms-api.jar" -Force
if (Test-Path "$out\web-console") { Remove-Item -Recurse -Force "$out\web-console" }
Copy-Item "$server\web_console\dist" "$out\web-console" -Recurse
Copy-Item "$PSScriptRoot\files\*" $out -Force
Copy-Item "$PSScriptRoot\README.md" $out -Force
Copy-Item "$PSScriptRoot\application.properties.example" "$out\config" -Force

# The settings hold the database password: created once, never overwritten by a later run.
$settings = "$out\config\application.properties"
if (-not (Test-Path $settings)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = -join ($bytes | ForEach-Object { '{0:x2}' -f $_ })
    (Get-Content "$PSScriptRoot\application.properties.example" -Raw).Replace('JWT_SECRET', $secret) |
        Set-Content $settings -Encoding ascii -NoNewline
    Write-Warning "Created $settings - fill in DB_HOST, DB_USER and DB_PASSWORD before uploading."
}
Write-Host "Ready to upload: $out"
