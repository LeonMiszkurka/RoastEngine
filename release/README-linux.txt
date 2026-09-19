RoastEngine - Linux and ChromeOS
================================

Start the game:    double-click RoastEngine.sh, or run  ./RoastEngine.sh  in a terminal
Start the Creator: bin/RoastEngine Creator

Java is included; nothing else needs installing. Your mods, settings and logs live in
~/RoastEngine. If something goes wrong, ~/RoastEngine/logs/game-latest.log says why -
please attach it when you report a bug.

Chromebooks
-----------
1. Settings > Advanced > Developers > Linux development environment > Turn on.
2. Download the zip that matches your Chromebook: linux-x64 for Intel/AMD, linux-arm64 for ARM
   (Settings > About ChromeOS > Additional details shows which).
3. Move the zip into "Linux files" in the Files app, then open the Terminal app and run:
       unzip RoastEngine-*-linux-*.zip
       RoastEngine/RoastEngine.sh
The game needs OpenGL 3.3. Most Chromebooks from 2020 on have it; on older ones the window
may not open, and the log will say so.
