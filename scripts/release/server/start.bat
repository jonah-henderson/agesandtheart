@echo off
rem Ages and the Art @VERSION@ - starts the server, backs the world up before every start, and starts it
rem again whenever it stops. Close this window (or press Ctrl+C during the countdown) to keep it stopped.
setlocal
cd /d "%~dp0"

rem Java 25. Point this at a java.exe if the one on PATH is older.
set JAVA=java
set MEMORY=@SERVER_MEMORY@

if not exist server.properties (
    powershell -NoProfile -ExecutionPolicy Bypass -File setup.ps1
    if errorlevel 1 goto :eof
)

:run
powershell -NoProfile -ExecutionPolicy Bypass -File backup.ps1
"%JAVA%" -Xms%MEMORY% -Xmx%MEMORY% -jar fabric-server-launch.jar nogui
echo.
echo The server stopped. Starting it again in 15 seconds; press Ctrl+C to keep it stopped.
timeout /t 15
goto :run
