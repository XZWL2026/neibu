param(
  [ValidateSet('Start','Stop','Status','Prepare','Verify')][string]$Action='Start',
  [switch]$Rebuild
)
$ErrorActionPreference='Stop'
$dockerRoot=$PSScriptRoot
$repoRoot=[IO.Path]::GetFullPath((Join-Path $dockerRoot '../..'))
$stateRoot=Join-Path $dockerRoot 'target'
Set-Location -LiteralPath $repoRoot
New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
$composeArgs=@('compose','--project-directory',$dockerRoot,'--env-file',"$stateRoot/.env.compose",'-f',"$stateRoot/compose.json")
if($Action -eq 'Stop') { & docker @composeArgs stop; exit $LASTEXITCODE }
if($Action -eq 'Status') { & docker @composeArgs ps; exit $LASTEXITCODE }
if($Action -eq 'Verify') { & python "$dockerRoot/verify.py"; exit $LASTEXITCODE }
& docker info --format '{{.ServerVersion}}' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Start Docker Desktop before running this script'}
$missingBackend=!(Test-Path -LiteralPath "$repoRoot/gateways/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar")
$missingFrontend=@('user','admin','partner') | Where-Object { !(Test-Path -LiteralPath "$repoRoot/frontends/$_-web/dist/index.html") }
if($Rebuild -or $missingBackend) {
  & "$repoRoot/mvnw.cmd" -DskipTests package *> "$stateRoot/backend-build.log"
  if($LASTEXITCODE -ne 0){throw 'Backend build failed; see deployment/docker/target/backend-build.log'}
}
if($Rebuild -or $missingFrontend) {
  if(!(Test-Path -LiteralPath "$repoRoot/node_modules/.bin/vite.cmd")) {
    npm ci --prefer-offline --no-audit --no-fund *> "$stateRoot/npm-install.log"
    if($LASTEXITCODE -ne 0){throw 'Frontend dependency installation failed; see target/npm-install.log'}
  }
  $saved=@{}
  foreach($key in @('VITE_GATEWAY_BASE_URL','VITE_LOGIN_URL','VITE_USER_WEB_URL')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
  try {
    $env:VITE_GATEWAY_BASE_URL=''
    $env:VITE_USER_WEB_URL='http://127.0.0.1:18390'
    foreach($frontend in @('user','admin','partner')) {
      $env:VITE_LOGIN_URL=if($frontend -eq 'admin'){'/admin/login'}elseif($frontend -eq 'partner'){'http://127.0.0.1:18390/user/login'}else{'/user/login'}
      npm run build --workspace "@xzwl/$frontend-web" *> "$stateRoot/$frontend-web-build.log"
      if($LASTEXITCODE -ne 0){throw "Frontend build failed: $frontend; see target/$frontend-web-build.log"}
    }
  } finally {
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
  }
}
& python "$dockerRoot/environment.py"
if($LASTEXITCODE -ne 0){throw 'Docker environment preparation failed'}
if($Action -eq 'Prepare'){exit}
$upArgs=@('up','-d','--build','--wait','--wait-timeout','360')
if($Rebuild){$upArgs+='--force-recreate'}
& docker @composeArgs @upArgs
if($LASTEXITCODE -ne 0){throw 'Some containers are not ready; inspect Docker Compose logs'}
& python "$dockerRoot/verify.py"
if($LASTEXITCODE -ne 0){throw 'Local application verification failed'}
Write-Output 'User: http://127.0.0.1:18390/'
Write-Output 'Admin: http://127.0.0.1:18390/admin/login'
Write-Output 'Partner: http://127.0.0.1:18392/'
Write-Output 'Private credentials: deployment/docker/target/.env.credentials.txt'
