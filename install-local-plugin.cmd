@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-local-plugin.ps1" %*
exit /b %ERRORLEVEL%
