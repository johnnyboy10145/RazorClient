# Compatibility manifest envelope

The launcher accepts remote approvals only from the HTTPS endpoint configured in
the native launcher. Release builds require this envelope:

```json
{
  "schemaVersion": "1",
  "payload": "<base64 of canonical UTF-8 JSON>",
  "signature": "<base64 RSA-PSS/SHA-256 signature, 32-byte salt>"
}
```

The canonical payload contains `manifestVersion`, `issuedAt`, `expires` (Unix
seconds), and approval entries. Every approval entry contains `approved`,
`name`, `bakeHash`, `mappingsHash`, `executableHash`, `jvmHash`, `architecture`
(`x64`), and `adapterVersion`. The launcher checks the signature over the
SHA-256 digest of the exact decoded payload bytes, a maximum 30-day lifetime,
rollback state, the selected process/runtime fingerprint, and the known adapter
before using it. It never downloads code or native payloads from this service.

The envelope is limited to 1 MiB and its decoded payload to 512 KiB. Verified
envelopes and their accepted monotonic manifest version are written through
temporary files and atomically promoted into the local cache.

`compat-public-key.b64` is a Windows CNG `BCRYPT_RSAPUBLIC_BLOB` encoded as
Base64. The server must sign with the matching private key; the private key is
never stored in this repository.
