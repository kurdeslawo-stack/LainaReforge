@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\validate-decisions.ps1" %*
exit /b %ERRORLEVEL%
