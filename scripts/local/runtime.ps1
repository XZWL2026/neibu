param(
  [ValidateSet('Start','Stop','Status','Verify','RefreshProduction','Infrastructure')]
  [string]$Action='Start',
  [switch]$Rebuild
)
$ErrorActionPreference='Stop'
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$localRoot=Join-Path $repoRoot 'deployment/local'
$stateRoot=Join-Path $localRoot 'target'
$bundledPython=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$python=if(Test-Path -LiteralPath $bundledPython){$bundledPython}else{(Get-Command python -ErrorAction Stop).Source}
Set-Location -LiteralPath $repoRoot
New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
function Invoke-Runtime {
  param([string]$Command,[string[]]$Extra=@())
  & $python (Join-Path $localRoot 'runtime.py') $Command @Extra
  if($LASTEXITCODE -ne 0){throw "Local runtime action failed: $Command"}
}
function Wait-Services {
  $ports=@(8210,8211,8212,8213,8214,8215,8216,8217,8218,8219,8220,8200)
  for($attempt=0;$attempt -lt 120;$attempt++) {
    $ready=$true
    foreach($port in $ports){
      $client=New-Object Net.Sockets.TcpClient
      try{$client.Connect('127.0.0.1',$port)}catch{$ready=$false}finally{$client.Dispose()}
    }
    if($ready){Start-Sleep -Seconds 2; return}
    Start-Sleep -Seconds 1
  }
  throw 'Backend ports did not become ready; see deployment/local/target/logs'
}
if($Action -eq 'Stop') {
  Invoke-Runtime stop
  & docker compose --env-file (Join-Path $stateRoot '.env.compose') -f (Join-Path $localRoot 'compose.yml') --profile web stop
  if($LASTEXITCODE -ne 0){throw 'Could not stop local containers'}
  Write-Output 'Local services stopped. Database volumes retained.'
  exit
}
if($Action -eq 'Status'){Invoke-Runtime status; exit}
if($Action -eq 'Verify'){Invoke-Runtime verify; exit}
& docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
if($LASTEXITCODE -ne 0){
  # Launch the installed Desktop UI as a hidden background process.
  $dockerCli=(Get-Command docker -ErrorAction Stop).Source
  $desktopPath=[IO.Path]::GetFullPath((Join-Path (Split-Path $dockerCli) '../../Docker Desktop.exe'))
  if(Test-Path -LiteralPath $desktopPath){Start-Process -FilePath $desktopPath -WindowStyle Hidden | Out-Null}
  else{throw 'Start Docker Desktop and retry this command'}
  for($attempt=0;$attempt -lt 90;$attempt++){
    Start-Sleep -Seconds 2
    & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if($LASTEXITCODE -eq 0){break}
  }
  if($LASTEXITCODE -ne 0){throw 'Docker Engine did not become ready'}
}
$initial=!(Test-Path -LiteralPath (Join-Path $stateRoot '.env.services.json'))
$refresh=$Action -eq 'RefreshProduction'
if($refresh){Invoke-Runtime stop}
if($initial -or $refresh){Invoke-Runtime export; Invoke-Runtime prepare}
Invoke-Runtime infra
if($Action -eq 'Infrastructure'){exit}
if($Rebuild){
  Invoke-Runtime stop -Extra @('--adopt-existing')
  $maven=Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if($maven){& $maven.Source -o -DskipTests package *> (Join-Path $stateRoot 'backend-build.log')}
  else{& (Join-Path $repoRoot 'mvnw.cmd') -DskipTests package *> (Join-Path $stateRoot 'backend-build.log')}
  if($LASTEXITCODE -ne 0){throw 'Backend build failed; see target/backend-build.log'}
}
Invoke-Runtime start -Extra $(if($initial){@('--adopt-existing')}else{@()})
Wait-Services
Invoke-Runtime verify
if(!(Test-Path -LiteralPath (Join-Path $stateRoot 'import-report.json')) -or $refresh){
  Invoke-Runtime import
}
$missingFrontend=@('user','admin','partner') | Where-Object { !(Test-Path -LiteralPath (Join-Path $repoRoot "frontends/$_-web/dist/index.html")) }
if($Rebuild -or $missingFrontend){
  $saved=@{}
  foreach($key in @('VITE_GATEWAY_BASE_URL','VITE_USER_WEB_URL','VITE_LOGIN_URL')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
  try {
    $env:VITE_GATEWAY_BASE_URL=''
    $env:VITE_USER_WEB_URL='http://127.0.0.1:18090'
    $env:VITE_LOGIN_URL='/user/login'
    npm exec --workspace @xzwl/user-web -- vite build *> (Join-Path $stateRoot 'user-web-build.log')
    if($LASTEXITCODE -ne 0){throw 'User frontend build failed'}
    $env:VITE_LOGIN_URL='/admin/login'
    npm exec --workspace @xzwl/admin-web -- vite build --base / *> (Join-Path $stateRoot 'admin-web-build.log')
    if($LASTEXITCODE -ne 0){throw 'Admin frontend build failed'}
    $env:VITE_LOGIN_URL='http://127.0.0.1:18090/user/login'
    npm exec --workspace @xzwl/partner-web -- vite build *> (Join-Path $stateRoot 'partner-web-build.log')
    if($LASTEXITCODE -ne 0){throw 'Partner frontend build failed'}
  } finally {
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
  }
}
Invoke-Runtime web
Write-Output 'User: http://127.0.0.1:18090/'
Write-Output 'Admin: http://127.0.0.1:18090/admin/login'
Write-Output 'API portal: http://127.0.0.1:18090/api/login'
Write-Output 'Partner: http://127.0.0.1:18092/'
Write-Output 'Private credentials: deployment/local/target/.env.credentials.txt'
