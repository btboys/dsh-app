# DSH App

Android client and DeepSeek Harness plugin for using the computer's existing sessions from a phone.

The phone is a remote control. Sessions, tools, and workspaces stay on the computer. The plugin opens an HTTPS gateway; the app pairs once, then opens the same DSH page in a WebView. Discovery, approvals, file preview, diagnostics, and notifications are native.

## Computer

Install the plugin from this checkout, then open **设置 → 移动访问**. The gateway serves HTTPS by default and keeps one identity in `~/.dsh-app/tls`; the settings page shows the certificate fingerprint next to the pairing code. Generate a pairing code for a LAN address, or for a remote HTTPS address you already expose with Tailscale or your own reverse proxy. This app does not install a tunnel.

If your own reverse proxy terminates TLS in front of the gateway, set `tls: false` so the gateway speaks HTTP only on the loopback interface; the phone still only reaches it through the proxy's HTTPS origin.

Unpaired requests are not forwarded to DSH. A device token is sent only to the origin it was paired with. Revoke a phone from the same settings page.

## Phone

The Android project is in `apps/android`. Pushing `main` runs GitHub Actions and uploads a debug APK artifact. A local build needs Android SDK 35 and JDK 17:

```bash
cd apps/android
gradle wrapper
./gradlew :app:assembleDebug
```

On the same LAN, search for the computer, paste the pairing code **and the fingerprint shown on the computer**, then open the session. The phone refuses an HTTPS computer whose certificate does not match that fingerprint, so compare the two before pairing; a mismatch means the connection is being intercepted. Leaving the LAN requires the remote origin you configured on the computer.

A paired phone keeps the fingerprint. If the computer's identity is replaced, delete `~/.dsh-app/tls` on the computer and pair again; the phone will refuse the new certificate until you confirm it.

Notifications need the connection service to stay running. They stop if Android kills the app.

## Troubleshooting

If the phone cannot find the computer on the same LAN:

- Confirm that the phone and the computer are on the same network, and that the gateway opened by the plugin is running; the LAN address is listed in **设置 → 移动访问**.
- If pairing still fails, check whether the pairing code has expired (a code is valid for five minutes) and generate a new one.
