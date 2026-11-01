# Google Drive backup and drawing export

Boundless Paint keeps a visible folder tree in the user's My Drive:

```text
Boundless Paint/
  Projects/   one .pnt archive per project
  Drawings/   PNG files saved from the studio
```

The tree is created by the app on the first connection. Do not create it manually: with the `drive.file`
scope the app can only see files it created itself, so a hand-made folder would be ignored and a second
folder with the same name would appear.

Connecting again (after **Disconnect Google Drive**, an app reinstall, or a token that was revoked) does not
create a second tree: the app searches Drive for `Boundless Paint`, `Projects` and `Drawings` by name and parent,
and only creates the folders that are missing. If several folders share a name, the oldest one is used so the
backups stay in the original tree.

## What is stored

- **Projects/**: one `.pnt` archive per project. The file is named `boundless-paint-<projectId>.pnt`, and
  the project id and display name are also written to Drive app properties. Backing up a renamed project
  updates the same remote file instead of creating a duplicate.
- **Drawings/**: the flattened canvas as a PNG, written by **Save drawing as PNG** in the Drive sheet. Each
  save gets a timestamp in its name, so earlier drawings are not overwritten.
- The rest of the user's Drive is neither visible nor accessible.

Restoring a project downloads the archive and imports it as a gallery project. If the gallery has no project
with the archived id, the id is kept so later syncs update the same remote backup; if that project already
exists, a fresh id is generated and the restore becomes a copy.

## Auto-sync

**Sync after saving** (enabled by default once an account is linked, and switchable in the Drive sheet) backs the
open project up after an explicit save: the Save command in the studio, or leaving the studio for Home. The
two-second autosave does not upload, so a long stroke session produces one remote write per deliberate save, not
one every two seconds. The upload runs in the background after the project file is written; the Drive sheet's
**Last sync** line and its backup list update when it finishes.

Auto-sync never uploads a project while another Drive operation is running. If the upload fails, the Drive sheet
shows the error on its next open, and a manual **Back up current project** retries.

## Authorization

The app uses Google Play services `AuthorizationClient` with one scope:

```text
https://www.googleapis.com/auth/drive.file
```

The first request shows Google's account chooser and consent sheet. Once the grant exists, the same call returns
a cached or silently refreshed access token. The app never stores OAuth tokens, refresh tokens, client secrets
or redirect URIs; access tokens are held by Google Play services. The linked account's email and display name
are cached locally so the Drive sheet can show who is connected. **Disconnect Google Drive** clears that local
link; it does not revoke the Google grant, so reconnecting does not always show the consent sheet. To remove the
app from the account entirely, use Google Account → Third-party apps and services.

Google matches the app to an **Android OAuth client** by package name and signing certificate. No client id or
JSON file is compiled into the app.

## Google Cloud Console setup

1. Create or select the `boundless-paint` project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable **Google Drive API** under **APIs & Services → Library**.
3. Under **Google Auth Platform → Data Access**, add the scope
   `https://www.googleapis.com/auth/drive.file`. The old `drive.appdata` scope is no longer used and can be
   removed.
4. Under **Clients**, create an **Android** OAuth client for the release certificate:
   - Package name: `app.painter`
   - SHA-1: the release keystore fingerprint (`FA:4B:04:09:FF:77:D7:64:1E:62:C7:6B:0B:96:83:5B:24:6D:94:AD`
     for the current key).
5. Optionally create a second Android client for the standard Android debug keystore. The local debug build is
   signed with the release key when `PAINTER_KEYSTORE_PROPERTIES` (or `~/.painter/keystore.properties`) exists,
   so it uses the release client; the debug client is only used for builds signed with the debug certificate.
6. If the OAuth consent screen is in **Testing**, add the Google account that will test the app under **Test
   users**. The unverified-app warning is expected; tap **Continue**.
7. The downloaded `client_secret_*.json` files are not needed by the app. Keep them outside git; `secret/` and
   `*client_secret*.json` are ignored by this repository.

## Requirements and limits

- Google Play services must be installed and up to date; the app targets Android 10+.
- Sync is manual by default except for **Sync after saving**: **Back up current project**, **Back up all
  projects** and **Save drawing as PNG** stay explicit, while auto-sync covers the open project after a save.
  There is one project backup per project, not a version history.
- Large projects are streamed from a temporary archive in the app cache; the archive is removed after upload.
- If the user deletes the `Boundless Paint` tree, the next operation recreates it. Existing remote backups are
  not restored by that operation.
