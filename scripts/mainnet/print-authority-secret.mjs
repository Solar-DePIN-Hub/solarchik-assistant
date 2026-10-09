#!/usr/bin/env node
// Prints the base58 secret of out/collection-authority-<cluster>.json for `wrangler secret put` (stdin, never committed).
import { readFileSync } from "node:fs";
import bs58 from "bs58";
const cluster = process.argv[2] || "mainnet";
process.stdout.write(bs58.encode(Uint8Array.from(JSON.parse(readFileSync(new URL(`./out/collection-authority-${cluster}.json`, import.meta.url), "utf8")))));
