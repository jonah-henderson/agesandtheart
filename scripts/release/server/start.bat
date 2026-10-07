@echo off
rem Ages and the Art @VERSION@ - backs the world up, then runs the server until it stops. Nothing restarts
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

powershell -NoProfile -ExecutionPolicy Bypass -File backup.ps1
"%JAVA%" -Xms%MEMORY% -Xmx%MEMORY% -jar fabric-server-launch.jar nogui
echo.
echo The server has stopped.
pause
