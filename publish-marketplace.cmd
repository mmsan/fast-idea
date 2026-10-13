@echo off
setlocal
set "RELEASE_PWSH="
for /f "delims=" %%P in ('where pwsh.exe 2^>nul') do if not defined RELEASE_PWSH set "RELEASE_PWSH=%%P"
if not defined RELEASE_PWSH if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "RELEASE_PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined RELEASE_PWSH if exist "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe" set "RELEASE_PWSH=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe"
if not defined RELEASE_PWSH (
  echo PowerShell 7 is required. Install it and add pwsh.exe to PATH.
  exit /b 1
)
"%RELEASE_PWSH%" -NoProfile -File "%~dp0publish-marketplace.ps1" %*
exit /b %ERRORLEVEL%
