# Hermes Mobile signing policy

Hermes Mobile releases use a dedicated signing identity. Do not reuse this key
for Tacet, Iris Mobile, or another Android application.

## Application identity

- Application ID: `sh.slb.hermesmobile`
- Key alias: `hermes-mobile`
- Certificate subject: `CN=Hermes Mobile Release, OU=Android, O=SLB`
- Certificate expiry: 2126-06-25
- SHA-256 certificate fingerprint:

  ```text
  C8:F7:21:07:7E:A1:37:CF:A0:CA:B9:DD:C4:3A:BF:A1:
  5E:59:AF:D5:DE:18:73:22:4B:19:27:8F:1F:2B:68:08
  ```

The public certificate is in
`signing/hermes-mobile-release-certificate.pem`. It does not contain the
private key.

## Private material

The private material is deliberately outside the repository:

- `~/.config/secrets/hermes-mobile-release.p12` is a password-encrypted
  PKCS#12 keystore with mode `0600`.
- `~/.config/secrets/hermes-mobile-release.env.cred` contains the signing
  password and alias, encrypted with `systemd-creds --user` and mode `0600`.

The `systemd-creds` file is bound to this WSL installation. It is not a
portable backup. Before publishing the first release, store the password in a
separate password manager and back up the PKCS#12 file through the normal
encrypted backup system. Test restoring both before treating the release key
as durable.

Losing this key prevents normal Android updates to an installed
`sh.slb.hermesmobile` application. Exposing it lets someone produce APKs that
Android recognizes as signed by the same publisher.

## GitHub Actions

The release workflow reads these repository secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Secrets must be populated without printing their values. The workflow builds
the generic `hermesRelease` variant, verifies it with `apksigner`, requires one
signer matching the pinned public certificate, and creates a draft GitHub
release.

## Release verification

Every published APK must report the fingerprint above:

```sh
apksigner verify --verbose --print-certs hermes-mobile-vX.Y.Z.apk
```

Also verify the adjacent SHA-256 checksum before installation.
