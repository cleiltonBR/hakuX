# HakuX Store hosting providers

The store accepts public HTTPS mirror URLs in its remote catalog. The provider registry
recognizes these hosting platforms and supplies a display name when a manifest does not
specify one.

## Supported hosting platforms

| Provider | Best use | Transfer policy / caveat |
| --- | --- | --- |
| Cloudflare R2 | Primary host for large public files | No internet egress charge; Standard includes 10 GB-month of storage and request allowances. Use a custom domain for production. The `r2.dev` development hostname is rate-limited. |
| Backblaze B2 | Secondary mirror / archive | Public buckets support direct downloads and byte-range requests. Free egress is up to 3x average stored data; monitor account usage. |
| GitHub Releases | Homebrew, demos, patches and smaller authorized packages | No published bandwidth quota for release assets, but each asset must be smaller than 2 GiB. Split larger files into verified parts. |

This is a hosting compatibility list, not a list of game download URLs. The HakuX team
must own or have permission to publish every file. No game files, credentials, API keys,
or third-party mirrors are bundled in the application.

## Publishing a mirror

1. Upload an authorized file to a public bucket/release.
2. Use its final HTTPS download URL (not a landing page or countdown page).
3. Add the URL to the game's `mirrors` array in the catalog manifest.
4. Include the exact byte size and SHA-256 checksum when available.
5. Mark `direct` and `supportsRanges` only after testing the actual URL.
6. HakuX probes each mirror. Latency is not a speed test; the app must measure transfer throughput before ranking by MB/s.

Provider pricing, quotas and terms can change. The published policy is not a promise of
unlimited account usage or a guaranteed download speed.
