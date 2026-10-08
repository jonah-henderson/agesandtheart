@echo off
rem Ages and the Art @VERSION@ - updates the mods, backs the world up, then runs the server until it stops. Nothing restarts
rem it: run this again to start it again.
setlocal
cd /d "%~dp0"

rem Java 25. Point this at a java.exe if the one on PATH is older.
set JAVA=java
set MEMORY=@SERVER_MEMORY@

if not exist server.properties (
    powershell -NoProfile -ExecutionPolicy Bypass -File setup.ps1
    if errorlevel 1 goto :eof
)

rem Installs and updates the mods from the pack, so a release needs nothing replaced here.
"%JAVA%" -jar packwiz-installer-bootstrap.jar -g -s server @PACK_URL@
if errorlevel 1 (
    echo.
    echo The mods could not be updated from the pack.
    choice /m "Start with the mods already here"
    if errorlevel 2 goto :eof
)

powershell -NoProfile -ExecutionPolicy Bypass -File backup.ps1
"%JAVA%" -Xms%MEMORY% -Xmx%MEMORY% -jar fabric-server-launch.jar nogui
echo.
echo The server has stopped.
pause
