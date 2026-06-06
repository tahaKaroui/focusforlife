# Firebase setup (cross-device sync)

FocusForLife can keep its usage quota in sync across your machines (Linux,
Windows, Android) through a Firebase Realtime Database. Sync is **optional** —
leave the config fields empty and each device just enforces its own quota.

All your devices sign in to **one shared account**, so they share a single
`uid` and therefore one namespace: `/users/{uid}/devices/{deviceId}`. The
database rules lock that namespace to its owner, so nobody else can read or
write your data.

## 1. Create a Firebase project

1. Go to <https://console.firebase.google.com/> and create a project.
2. **Build → Realtime Database → Create database.** Note the database URL, e.g.
   `https://<project>-default-rtdb.<region>.firebasedatabase.app`.
3. **Build → Authentication → Get started → Sign-in method → Email/Password →
   Enable.**
4. **Authentication → Users → Add user.** Create one account (any email +
   password); this is the shared account every device will use.

## 2. Lock down the database rules

Deploy the rules in [`database.rules.json`](../database.rules.json):

```json
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid"
      }
    }
  }
}
```

Paste them into **Realtime Database → Rules** and publish, or with the Firebase
CLI: `firebase deploy --only database`.

## 3. Find your Web API key

**Project settings → General → Your apps → Web API key** (also present in any
`google-services.json` as `current_key`). The desktop daemons need it for the
REST sign-in endpoint.

## 4. Configure the desktop daemons (Linux / Windows)

Copy the example config to a local, gitignored file and fill it in:

```bash
cp config/example.toml config/linux.local.toml   # or windows.local.toml
```

```toml
[sync]
firebase_db_url  = "https://<project>-default-rtdb.<region>.firebasedatabase.app"
firebase_api_key = "<web-api-key>"
firebase_email   = "<shared-account-email>"
firebase_password = "<shared-account-password>"
device_id        = "linux"      # unique per device: linux / windows / ...
sync_interval_seconds = 10
```

Run the daemon with `--config config/linux.local.toml`. Files matching
`config/*.local.toml` are gitignored so credentials never get committed.

## 5. Configure the Android app

1. In the Firebase console add an Android app with package
   `dev.focusforlife.android` and download its `google-services.json` into
   `android/app/` (gitignored).
2. Add the shared-account credentials to `android/local.properties` (gitignored):

   ```properties
   ffl.firebase.email=<shared-account-email>
   ffl.firebase.password=<shared-account-password>
   ffl.firebase.dbUrl=https://<project>-default-rtdb.<region>.firebasedatabase.app
   ```

   These are injected into `BuildConfig` at build time. If they're absent the
   app simply runs standalone with no sync.

## How it works

- Desktop daemons authenticate via the Identity Toolkit REST API
  (`accounts:signInWithPassword`), cache the id token, refresh it ~1 min before
  expiry, and read/write `/users/{uid}/devices/{deviceId}.json?auth=<idToken>`.
- The Android app uses `FirebaseAuth.signInWithEmailAndPassword` and the
  Realtime Database SDK against the same `/users/{uid}/devices` path.
- Each device writes its own `{deviceId}` node and sums the others' usage, so
  the quota is shared across all devices.
