# Putting the RoastEngine server on AWS (sleeps when nobody plays)

The server runs on a small **EC2** machine that switches itself off after 15 minutes with nobody on.
The game's server list shows it as `server_1`. Opening the list only asks the tiny **Lambda**
function (the "wake-up link") how the server is. When someone clicks **Wake & Join**, the link starts
the machine, the game waits about a minute, and joins. You only pay while it's on.

```
Wake & Join ─────────► wake-up link (Lambda) ──► starts the EC2 machine, reports its address
                                                         │
     game joins as soon as the server answers ◄──────────┘
     ...nobody on for 15 min ──► server powers the machine off ──► charges stop
```

**Cost** (typical region; prices shown on AWS pages are the ones that count):

| | |
|---|---|
| Machine, while on | ~$0.013 an hour (machine + its public address) |
| Its disk, always | ~$0.65 a month |
| Wake-up link | $0 (Lambda's free allowance is 1 million calls a month) |
| Data | first 100 GB a month free |

For example, 10 hours of play a week comes to about **$1.30 a month**. Worst case, someone keeps it
awake all month: about $10. Step 6 sets up an email alert for that.

Everything happens on the AWS website, apart from a few commands in your Mac's Terminal. It takes
about 30 minutes, once. Pick **one region** (top right of the AWS console, e.g. *Europe (Frankfurt)*),
the one closest to most players, and stay in it for every step.

---

## 1. Build the server zip (on your Mac)

```
cd /Volumes/Projects/RoastEngine/Game
./gradlew :Server:distZip
```

This makes `Server/build/distributions/roastengine-server.zip`.

## 2. Create the machine (EC2)

AWS console, search **EC2**, **Launch instance**:

1. **Name:** `roastengine-server`
2. **Application and OS Images:** **Ubuntu**, *Ubuntu Server 24.04 LTS*, architecture **64-bit (Arm)**.
3. **Instance type:** **t4g.micro** (1 GB). If your account shows another type marked *Free tier
   eligible*, you can use that one instead (switch the architecture to *64-bit (x86)* if it needs it).
4. **Key pair:** **Create new key pair**, name `roastengine`, type RSA, format `.pem`. It downloads.
   Keep it: it's how your Mac logs in to the machine.
5. **Network settings:** keep *Create security group*, tick **Allow SSH traffic from: My IP**. Then
   **Edit**, then **Add security group rule**: Type *Custom TCP*, Port **25570**, Source **Anywhere
   (0.0.0.0/0)**. This is the port players join on.
6. **Storage:** leave the default (8 GB).
7. **Advanced details**: check **Shutdown behavior** is **Stop** (not Terminate!), and set
   **Termination protection** to **Enable** so the machine can't be deleted by accident.
8. **Launch instance**.

Open the instance and write down its **Instance ID** (looks like `i-0abc123def4567890`).

## 3. Install the server (Mac Terminal)

On the instance page, copy its **Public IPv4 address**. It changes every time the machine starts,
which doesn't matter to players (the wake-up link looks it up), but you need the current one here.

```
mv ~/Downloads/roastengine.pem ~/.ssh/ && chmod 400 ~/.ssh/roastengine.pem

scp -i ~/.ssh/roastengine.pem Server/build/distributions/roastengine-server.zip ubuntu@PUBLIC_IP:
ssh -i ~/.ssh/roastengine.pem ubuntu@PUBLIC_IP
```

Type `yes` if it asks about the fingerprint. You're now on the machine. Run:

```
sudo apt-get update && sudo apt-get install -y unzip
unzip -o roastengine-server.zip
sudo bash roastengine-server/deploy/setup.sh
```

It ends with **"RoastEngine server is running."** Type `exit` to leave.

> The 15-minute sleep timer is already counting, so if you take a break the machine turns itself
> off. That's it working. Start it again from the EC2 page (**Instance state > Start**) when you
> need it.

## 4. Create the wake-up link (Lambda)

AWS console, search **Lambda**, **Create function**:

1. **Author from scratch**. Name `roastengine-wake`, Runtime **Python 3** (the newest offered),
   Architecture **arm64**. **Create function**.
2. **Code:** open `lambda_function.py` in the editor, replace everything in it with the contents of
   [`Server/aws/wake_lambda.py`](aws/wake_lambda.py), then click **Deploy**.
3. **Configuration > Environment variables > Edit > Add:** key `INSTANCE_ID`, value your instance
   ID from step 2. Save.
4. **Configuration > General configuration > Edit:** Timeout **10 sec**. Save.
5. **Configuration > Permissions:** click the **Role name** link (opens IAM), then **Add permissions >
   Create inline policy > JSON**. Paste [`Server/aws/wake-policy.json`](aws/wake-policy.json) with
   `PUT-YOUR-INSTANCE-ID-HERE` replaced by your instance ID. **Next**, name it
   `start-roastengine-server`, **Create policy**. This lets the function start that one machine and
   nothing else.
6. **Configuration > Function URL > Create function URL:** Auth type **NONE**, **Save**. Copy the URL
   (it looks like `https://abc123.lambda-url.eu-central-1.on.aws/`).

**Test it:** open the URL in your browser. You should see `{"status": "ready", ...}` if the machine
is on, or `{"status": "starting"}`, in which case the machine starts. Check the EC2 page.

## 5. Put the link in the game

Paste the URL into [`src/main/resources/multiplayer-defaults.properties`](../src/main/resources/multiplayer-defaults.properties):

```
server.1.name=server_1
server.1.wakeUrl=https://abc123.lambda-url.eu-central-1.on.aws/
```

Rebuild the game. `server_1` on the Multiplayer screen now shows its status and can be joined.

## 6. Set a spending alert

AWS console, search **Budgets**, **Create budget**: **Use a template**, then **Monthly cost budget**.
Amount **$5**, your email, **Create budget**. AWS emails you if the month's bill heads past it.

---

## Looking after it

The machine must be on to log in: click **Wake & Join** on server_1 (or **Start** on the EC2 page),
then use the current **Public IPv4 address**:

```
ssh -i ~/.ssh/roastengine.pem ubuntu@PUBLIC_IP
```

| To... | Run on the machine |
|---|---|
| Watch joins, chat, errors live | `sudo journalctl -u roastengine-server -f` (Ctrl+C stops watching) |
| Change the name, message, max players, world or sleep time | `sudo nano /opt/roastengine-server/data/server.properties`, then `sudo systemctl restart roastengine-server` |
| Update the wake-up link | Paste the new [`wake_lambda.py`](aws/wake_lambda.py) into the Lambda's code editor and click **Deploy** |
| Update to a new version | `scp` the new zip over as in step 3, then `unzip -o roastengine-server.zip && sudo bash roastengine-server/deploy/setup.sh` (settings are kept) |

In `server.properties`:

- `idleShutdownMinutes`: minutes with nobody on before it sleeps. `0` means never sleep, which
  charges the whole time.
- `world`: leave it empty and the first player in picks the world (and mods) each time. Set it to
  a world mod's name to always play that one.

When the network messages change, `Protocol.VERSION` goes up, and the old game and the new server
refuse each other with a clear message. Update both together.

## Removing it all

1. EC2: select the instance, **Actions > Instance settings > Change termination protection**, turn it
   off, then **Instance state > Terminate**. Its disk goes with it.
2. Lambda: select `roastengine-wake`, **Actions > Delete**.
3. Remove `server.1.wakeUrl` from the game so server_1 shows as "not set up".

## If something's wrong

- **Browser shows "Forbidden" for the wake-up link:** in Lambda, open **Configuration > Permissions**
  and under *Resource-based policy* there should be a statement allowing public access to the
  function URL. Delete the function URL and create it again with Auth type **NONE**.
- **Wake-up link says "Could not reach the server machine":** the policy in step 4.5 is missing,
  or the instance ID is wrong. The function's **Monitor > View CloudWatch logs** shows the exact error.
- **Game waits, then "did not wake up in time":** the machine started but the server didn't. Log
  in and check `sudo journalctl -u roastengine-server -n 50`. Also check port **25570 / TCP** is in
  the security group (EC2, then instance, then **Security** tab).
- **`ssh` times out:** your home IP changed since step 2. In the security group, edit the SSH rule
  and pick **My IP** again.
- **Machine never turns off:** check `idleShutdownMinutes` isn't `0`, and that **Shutdown behavior**
  is *Stop*.
