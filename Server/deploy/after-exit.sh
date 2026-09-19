#!/usr/bin/env bash
# Runs (as root) each time the RoastEngine server process ends. systemd passes the exit code in
# EXIT_STATUS. 42 means the server was idle for idleShutdownMinutes, so the machine powers off -
# on EC2 that stops the instance, and it is started again by the wake-up function.
if [ "${EXIT_STATUS:-}" = "42" ]; then
    logger -t roastengine "Server idle - powering off"
    systemctl poweroff
fi
