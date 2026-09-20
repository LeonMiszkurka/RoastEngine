"""
CoffeeBrew Interactive accounts (AWS Lambda, Python 3.12+, DynamoDB).

One account works across every CoffeeBrew Interactive game. Games talk to this function's URL
over HTTPS; everything is a POST with a JSON body, answered with JSON.

Called by the game (the player's own computer):
  {"action": "register", "username": "...", "password": "..."}  -> {"ok": true, "token", "account"}
  {"action": "login",    "username": "...", "password": "..."}  -> {"ok": true, "token", "account"}
  {"action": "me",       "token": "..."}                        -> {"ok": true, "account"}
  {"action": "logout",   "token": "..."}                        -> {"ok": true}
  {"action": "ticket",   "token": "..."}                        -> {"ok": true, "ticket"}

Called by game servers, which prove who they are with SERVER_KEY:
  {"action": "redeem", "serverKey", "ticket"}                   -> {"ok": true, "account"}
  {"action": "ban",    "serverKey", "by", "target", "reason"}   -> {"ok": true}
  {"action": "unban",  "serverKey", "by", "target"}             -> {"ok": true}

Failures are {"ok": false, "error": "a message fit to show the player"}.

A session token stays on the player's computer and is never sent to a game server, because the
game connection is not encrypted. To join, the game swaps it for a ticket that works once, for a
minute; the server redeems it here to learn who is joining.

Ranks ("normal", "moderator", "admin", "owner", lowest first) are only ever changed by editing the
account in the DynamoDB console - nothing here can raise anyone's rank. Moderators and above can
ban; admins and above can unban; and nobody can touch their own rank or higher.

Settings (Configuration > Environment variables):
  ACCOUNTS_TABLE   default CoffeeBrewAccounts   partition key: username (String, lower case)
  SESSIONS_TABLE   default CoffeeBrewSessions   partition key: tokenHash (String); TTL on expiresAt
  SERVER_KEY       a long random secret, also put in each game server's server.properties
"""

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import time
import uuid

import boto3

dynamodb = boto3.resource("dynamodb")
accounts = dynamodb.Table(os.environ.get("ACCOUNTS_TABLE", "CoffeeBrewAccounts"))
sessions = dynamodb.Table(os.environ.get("SESSIONS_TABLE", "CoffeeBrewSessions"))

SESSION_SECONDS = 30 * 24 * 3600
TICKET_SECONDS = 60
MAX_FAILED_LOGINS = 8
LOCK_SECONDS = 15 * 60
USERNAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")
RANKS = ("normal", "moderator", "admin", "owner")
RANK_LEVEL = {rank: level for level, rank in enumerate(RANKS)}


class Refused(Exception):
    """A request that fails with a message for the player."""


def lambda_handler(event, context):
    try:
        body = json.loads(_raw_body(event) or "{}")
        if not isinstance(body, dict):
            raise Refused("Bad request.")
        handler = ACTIONS.get(body.get("action"))
        if handler is None:
            raise Refused("Unknown action.")
        return _reply(200, {"ok": True, **handler(body)})
    except Refused as refused:
        return _reply(400, {"ok": False, "error": str(refused)})
    except (json.JSONDecodeError, UnicodeDecodeError):
        return _reply(400, {"ok": False, "error": "Bad request."})
    except Exception as error:  # noqa: BLE001 - never leak details to the caller
        print(f"Accounts failed: {error!r}")
        return _reply(500, {"ok": False, "error": "The account service had a problem. Try again soon."})


def _raw_body(event):
    raw = (event or {}).get("body") or ""
    if (event or {}).get("isBase64Encoded"):
        raw = base64.b64decode(raw).decode("utf-8")
    return raw


def _reply(status, body):
    return {"statusCode": status,
            "headers": {"Content-Type": "application/json", "Cache-Control": "no-store"},
            "body": json.dumps(body)}


# --- Passwords ---------------------------------------------------------------------

def _hash_password(password, salt):
    # scrypt: slow and memory-hungry on purpose, so a stolen table is hard to crack.
    return hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2 ** 14, r=8, p=1, dklen=32)


def _check_password(account, password):
    salt = base64.b64decode(account["passwordSalt"])
    expected = base64.b64decode(account["passwordHash"])
    return hmac.compare_digest(_hash_password(password, salt), expected)


def _token_hash(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


# --- Accounts ----------------------------------------------------------------------

def _public(account):
    """What callers may see: never the password hash, salt or login counters."""
    return {"accountId": account["accountId"], "username": account["displayName"],
            "rank": account.get("rank", "normal") if account.get("rank") in RANKS else "normal",
            "banned": bool(account.get("banned")), "banReason": account.get("banReason", "")}


def _get_account(username):
    if not isinstance(username, str) or not username:
        return None
    return accounts.get_item(Key={"username": username.lower()}).get("Item")


def _new_session(account, kind, seconds):
    token = secrets.token_urlsafe(32)
    sessions.put_item(Item={"tokenHash": _token_hash(token), "kind": kind,
                            "username": account["username"], "expiresAt": int(time.time()) + seconds})
    return token


def _session_account(token, kind="session", consume=False):
    if not isinstance(token, str) or not token:
        raise Refused("Please sign in again.")
    key = {"tokenHash": _token_hash(token)}
    item = sessions.get_item(Key=key).get("Item")
    # DynamoDB removes expired items only eventually, so the expiry is checked here too.
    if not item or item.get("kind") != kind or int(item["expiresAt"]) < time.time():
        raise Refused("Please sign in again." if kind == "session" else "That join ticket has expired.")
    if consume:
        sessions.delete_item(Key=key)
    account = _get_account(item["username"])
    if not account:
        raise Refused("That account no longer exists.")
    return account


def register(body):
    username = body.get("username")
    password = body.get("password")
    if not isinstance(username, str) or not USERNAME.match(username):
        raise Refused("Usernames are 3 to 16 letters, numbers or underscores.")
    if not isinstance(password, str) or len(password) < 8 or len(password) > 200:
        raise Refused("Passwords need at least 8 characters.")
    salt = secrets.token_bytes(16)
    account = {"username": username.lower(), "displayName": username, "accountId": str(uuid.uuid4()),
               "passwordSalt": base64.b64encode(salt).decode(),
               "passwordHash": base64.b64encode(_hash_password(password, salt)).decode(),
               "rank": "normal", "createdAt": int(time.time())}
    try:
        accounts.put_item(Item=account, ConditionExpression="attribute_not_exists(username)")
    except accounts.meta.client.exceptions.ConditionalCheckFailedException:
        raise Refused("That username is taken.")
    return {"token": _new_session(account, "session", SESSION_SECONDS), "account": _public(account)}


def login(body):
    account = _get_account(body.get("username"))
    password = body.get("password")
    now = int(time.time())
    if account and int(account.get("lockedUntil", 0)) > now:
        raise Refused("Too many wrong passwords. Try again in 15 minutes.")
    if not account or not isinstance(password, str) or not _check_password(account, password):
        if account:
            failed = int(account.get("failedLogins", 0)) + 1
            update = {"failedLogins": failed}
            if failed >= MAX_FAILED_LOGINS:
                update = {"failedLogins": 0, "lockedUntil": now + LOCK_SECONDS}
            _set(account["username"], update)
        raise Refused("Wrong username or password.")
    if account.get("failedLogins"):
        _set(account["username"], {"failedLogins": 0})
    return {"token": _new_session(account, "session", SESSION_SECONDS), "account": _public(account)}


def me(body):
    return {"account": _public(_session_account(body.get("token")))}


def logout(body):
    token = body.get("token")
    if isinstance(token, str) and token:
        sessions.delete_item(Key={"tokenHash": _token_hash(token)})
    return {}


def ticket(body):
    account = _session_account(body.get("token"))
    return {"ticket": _new_session(account, "ticket", TICKET_SECONDS)}


# --- Game servers ------------------------------------------------------------------

def _require_server(body):
    expected = os.environ.get("SERVER_KEY", "")
    given = body.get("serverKey")
    if len(expected) < 16 or not isinstance(given, str) or not hmac.compare_digest(given, expected):
        raise Refused("Unknown server.")


def redeem(body):
    _require_server(body)
    return {"account": _public(_session_account(body.get("ticket"), kind="ticket", consume=True))}


def _moderation(body, least_rank):
    """Checks the server, the moderator's rank and that the target ranks below them."""
    _require_server(body)
    by = _get_account(body.get("by"))
    target = _get_account(body.get("target"))
    if not by or RANK_LEVEL.get(by.get("rank"), 0) < RANK_LEVEL[least_rank]:
        raise Refused("You don't have permission to do that.")
    if not target:
        raise Refused(f"There's no account called {body.get('target')}.")
    if RANK_LEVEL.get(target.get("rank"), 0) >= RANK_LEVEL.get(by.get("rank"), 0):
        raise Refused(f"You can't do that to {target['displayName']}.")
    return by, target


def ban(body):
    by, target = _moderation(body, "moderator")
    reason = str(body.get("reason") or "No reason given")[:200]
    _set(target["username"], {"banned": True, "banReason": reason, "bannedBy": by["displayName"],
                              "bannedAt": int(time.time())})
    print(f"{by['displayName']} banned {target['displayName']}: {reason}")
    return {"account": _public({**target, "banned": True, "banReason": reason})}


def unban(body):
    by, target = _moderation(body, "admin")
    _set(target["username"], {"banned": False, "banReason": ""})
    print(f"{by['displayName']} unbanned {target['displayName']}")
    return {"account": _public({**target, "banned": False, "banReason": ""})}


def _set(username, values):
    names = {f"#k{i}": key for i, key in enumerate(values)}
    placeholders = {f":v{i}": value for i, value in enumerate(values.values())}
    expression = "SET " + ", ".join(f"#k{i} = :v{i}" for i in range(len(values)))
    accounts.update_item(Key={"username": username}, UpdateExpression=expression,
                         ExpressionAttributeNames=names, ExpressionAttributeValues=placeholders)


ACTIONS = {"register": register, "login": login, "me": me, "logout": logout, "ticket": ticket,
           "redeem": redeem, "ban": ban, "unban": unban}
