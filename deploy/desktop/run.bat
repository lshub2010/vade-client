@echo off
rem A5 launcher for the packaged desktop app (portable app-image, no install).
rem Build it first:  gradle :composeApp:createDistributable   (see README.md)
rem Extra args are passed through to the app, e.g.  run.bat --dark
setlocal
set "APP_DIR=%~dp0..\..\composeApp\build\compose\binaries\main\app\vade-ui"
if not exist "%APP_DIR%\vade-ui.exe" (
  echo [vade] app-image not found: %APP_DIR%
  echo [vade] build it first:  gradle :composeApp:createDistributable
  exit /b 1
)
"%APP_DIR%\vade-ui.exe" %*
endlocal
