# Mainnet agent NFT collection (one-time, run by Vadym)

Until this is done the app shows "Mainnet mint: coming soon" and the worker answers `503 MINT_NOT_READY`. Nothing here was run on mainnet; a devnet rehearsal was.

1. Confirm the treasury `8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic` (Pro mints pay 0.1 SOL there; 5 % royalties).
2. Fund a payer keypair with at least 0.01 SOL (rent ≈ 0.0029 SOL + fee). Keep it outside the repo.
3. `npm install`, then a dry run: `node create-collection.mjs --payer ~/vadym-mainnet-deployer.json` (reads only).
4. Create it: `node create-collection.mjs --payer ~/vadym-mainnet-deployer.json --send`. It prints the collection address and writes the separate update authority to `out/collection-authority-mainnet.json` (gitignored; it never holds SOL).
5. Worker: `node print-authority-secret.mjs mainnet | npx wrangler secret put COLLECTION_AUTHORITY_SECRET --name solarchik-screen`, set `[vars] MAINNET_COLLECTION` and `MAINNET_COLLECTION_AUTHORITY` in `worker/wrangler.screen.toml`, then `npx --yes wrangler@4 deploy --config wrangler.screen.toml` from `worker/`. Check `GET /agent/mint-config` says `ready: true`.
6. App: in `artifacts/solarchik-handoff/android/app/build.gradle.kts` set the `MAINNET_MINT_READY` buildConfigField to `true` and fill `MAINNET_COLLECTION` / `MAINNET_COLLECTION_AUTHORITY`, rebuild, release.
