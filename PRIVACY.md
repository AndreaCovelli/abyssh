# Privacy policy

Abyssh has no telemetry. It has no analytics, crash reporting, advertising, or accounts,
and the app itself sends nothing to its developer or to any other third party.

## Network connections

Abyssh connects only to the hosts you choose, using SSH, Tailscale SSH, or Mosh. Links you
tap in a terminal, and files you open from the file browser, go to another app on your
phone.

The hosts you connect to see what any SSH or Mosh server sees, such as your IP address and
username. Downloading Abyssh from GitHub Releases is subject to GitHub's own privacy
policy.

## Data on your device

Everything Abyssh stores stays on your device:

- Host profiles, including groups, favourites, and when you last connected to each host.
- Passwords, key passphrases, and private keys, which are encrypted with a key that the
  Android Keystore protects.
- The host keys you've trusted, and your settings.
- Files you open from a server, which are cached until Android clears the app's cache.

Android's own backup and device transfer leave all of this out. Your hosts and keys leave
the device only when you export an encrypted backup yourself, and that file goes wherever
you save it. The backup leaves out the host keys you've trusted and when you last
connected.

Uninstalling Abyssh or clearing its storage deletes all of this.

## Permissions

| Permission | Why Abyssh asks for it |
| --- | --- |
| Internet and network state | To connect to your hosts, and to reconnect when the network changes |
| Local network | To reach hosts on your local network |
| Foreground service | To keep sessions connected while the app is in the background |
| Notifications | To show that sessions are running |

Abyssh reads files only through the system file picker, which gives it the files you
choose and nothing else.

## Changes

Changes to this policy are made in this file, so its
[history](https://github.com/AndreaCovelli/abyssh/commits/main/PRIVACY.md) shows every
version. Questions are welcome in the
[issue tracker](https://github.com/AndreaCovelli/abyssh/issues).
