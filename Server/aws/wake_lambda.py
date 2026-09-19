"""
RoastEngine wake-up function (AWS Lambda, Python 3.12+).

The game's server list calls this function's URL. Opening the Multiplayer screen asks for the
status only (?action=status), which never starts anything; clicking Join calls it without that,
which starts the server's EC2 instance if it is asleep. Either way it says where to connect once
the server is up. It can do nothing else: its permissions only allow starting that one instance.

Replies (JSON):
  {"status": "asleep"}                                              (status only) stopped
  {"status": "ready",    "address": "3.120.45.67", "port": 25570}   running, connect now
  {"status": "starting"}                                            booting; ask again shortly
  {"status": "stopping"}                                            going to sleep; ask again, it
                                                                    is started once fully stopped
  {"status": "error",    "message": "..."}                          something is wrong with setup

Settings (Configuration > Environment variables):
  INSTANCE_ID   the server's instance id, e.g. i-0123456789abcdef0
  PORT          the game port, 25570 unless changed in server.properties
"""

import json
import os

import boto3

ec2 = boto3.client("ec2")


def reply(body, status_code=200):
    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json", "Cache-Control": "no-store"},
        "body": json.dumps(body),
    }


def lambda_handler(event, context):
    query = (event or {}).get("queryStringParameters") or {}
    status_only = query.get("action") == "status"
    instance_id = os.environ.get("INSTANCE_ID", "").strip()
    port = int(os.environ.get("PORT", "25570"))
    if not instance_id:
        return reply({"status": "error", "message": "INSTANCE_ID is not set on the wake-up function."}, 500)

    try:
        found = ec2.describe_instances(InstanceIds=[instance_id])
        instance = found["Reservations"][0]["Instances"][0]
        state = instance["State"]["Name"]

        if state == "running":
            address = instance.get("PublicIpAddress")
            if address:
                return reply({"status": "ready", "address": address, "port": port})
            return reply({"status": "starting"})  # running, but the address is not assigned yet
        if state == "pending":
            return reply({"status": "starting"})
        if state == "stopped" and status_only:
            return reply({"status": "asleep"})
        if state == "stopped":
            ec2.start_instances(InstanceIds=[instance_id])
            print(f"Starting {instance_id}")
            return reply({"status": "starting"})
        if state == "stopping":
            return reply({"status": "stopping"})
        # shutting-down / terminated: the instance is gone for good.
        return reply({"status": "error", "message": f"The server machine is {state}."}, 500)
    except Exception as error:  # noqa: BLE001 - any AWS error becomes a readable reply
        print(f"Wake-up failed: {error!r}")
        return reply({"status": "error", "message": "Could not reach the server machine. "
                                                    "Check the wake-up function's log."}, 500)
