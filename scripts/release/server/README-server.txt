Ages and the Art @VERSION@ - the beta server
==============================================

FIRST TIME
1. Install Java 25 (Temurin: https://adoptium.net), and check "java -version" says 25.
2. Unzip this into an empty folder and run start.bat. The first start installs the mods, writes server.properties, asks you
   to agree to Minecraft's EULA, and downloads the Minecraft server itself. start.bat runs the server
   until it stops and does not start it again: to start it, run start.bat; to stop it, type "stop".
3. In the server window, let the testers in by name:  whitelist add <name>
   Make yourself an operator the same way:              op <name>
4. Forward TCP port 25565 to this machine, and nothing else. RCON (25575) is for backups on this machine
   only; if Windows asks whether Java may use the network, allow private networks only.
5. Daily backups:  powershell -ExecutionPolicy Bypass -File backup.ps1 -Register
   start.bat also backs the world up before it starts the server. Backups land in backups\, the newest 14
   kept.

UPDATING TO A NEW RELEASE
Type "stop" in the server window and run start.bat again. It fetches the new release's mods from the pack
before it starts, and removes the old ones. Nothing needs unzipping unless this zip itself changes (a new
start.bat, say), and then you are told to.

The first start and every update need the internet. If the pack cannot be reached, start.bat asks whether to
start with the mods already here.

Players on an older release are turned away with a message telling them to restart their game, which
updates it.
