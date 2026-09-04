@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-review.ps1" %*
exit /b %ERRORLEVEL%
