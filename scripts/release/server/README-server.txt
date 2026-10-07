Ages and the Art @VERSION@ - the beta server
==============================================

FIRST TIME
1. Install Java 25 (Temurin: https://adoptium.net), and check "java -version" says 25.
2. Unzip this into an empty folder and run start.bat. The first start writes server.properties, asks you
   to agree to Minecraft's EULA, and downloads the Minecraft server itself.
3. In the server window, let the testers in by name:  whitelist add <name>
   Make yourself an operator the same way:              op <name>
4. Forward TCP port 25565 to this machine, and nothing else. RCON (25575) is for backups on this machine
   only; if Windows asks whether Java may use the network, allow private networks only.
5. Daily backups:  powershell -ExecutionPolicy Bypass -File backup.ps1 -Register
   start.bat also backs the world up before every start. Backups land in backups\, the newest 14 kept.

UPDATING TO A NEW RELEASE
1. Type "stop" in the server window, then close it before it restarts.
2. Delete the mods folder.
3. Unzip the new release over this folder, replacing files when asked. Your world, server.properties,
   whitelist and backups are not in the zip, so they are left alone.
4. Run start.bat.

Players on an older release are turned away with a message telling them to import the newest instance.
