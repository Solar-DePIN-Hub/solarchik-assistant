#!/usr/bin/env node
// Solarchik Agents: one-time Metaplex Core collection for agent NFTs on Solana MAINNET (or devnet for a rehearsal).
//
//   npm install                                   # once, in scripts/mainnet
//   node create-collection.mjs --payer ~/vadym-mainnet-deployer.json           # dry run: checks balance, sends nothing
//   node create-collection.mjs --payer ~/vadym-mainnet-deployer.json --send    # creates the collection (real SOL)
//
// --payer   Solana CLI keypair JSON that pays rent + fee (Vadym's funded key; never committed, never sent anywhere).
// --cluster mainnet (default) | devnet
// --rpc     optional RPC URL (default: the public node of the cluster)
// --send    actually send. Without it the script only reads balances and prints the plan.
//
// The collection's update authority is a SEPARATE key generated here (out/collection-authority-<cluster>.json).
// It never holds SOL; the worker uses it only to co-sign mints that pass the rules in worker/agent-mint.js.
// Royalties: 5% to the treasury on every agent in the collection.
import { readFileSync, writeFileSync, existsSync, mkdirSync, chmodSync } from "node:fs";
import { createUmi } from "@metaplex-foundation/umi-bundle-defaults";
import { createCollection, mplCore, ruleSet } from "@metaplex-foundation/mpl-core";
import { generateSigner, keypairIdentity, publicKey, sol, createSignerFromKeypair } from "@metaplex-foundation/umi";
import bs58 from "bs58";

const TREASURY = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic";
const NAME = "Solarchik Agents";
const URI = "https://raw.githubusercontent.com/Solar-DePIN-Hub/solarchik-assistant/main/scripts/mainnet/collection.json";
/** Collection account rent (~0.0029 SOL measured on devnet) + fee, with margin. */
export const NEEDED_SOL = 0.01;

function arg(name, fallback = "") {
  const i = process.argv.indexOf("--" + name);
  if (i < 0) return fallback;
  const v = process.argv[i + 1];
  return v && !v.startsWith("--") ? v : true;
}

const cluster = arg("cluster", "mainnet");
if (!["mainnet", "devnet"].includes(cluster)) throw new Error("--cluster must be mainnet or devnet");
const rpc = arg("rpc", cluster === "mainnet" ? "https://api.mainnet-beta.solana.com" : "https://api.devnet.solana.com");
const payerPath = arg("payer");
const send = arg("send", false) === true;
if (!payerPath || payerPath === true) {
  console.error("usage: node create-collection.mjs --payer <keypair.json> [--cluster mainnet|devnet] [--send]");
  process.exit(2);
}

const umi = createUmi(rpc).use(mplCore());
const payerKp = umi.eddsa.createKeypairFromSecretKey(Uint8Array.from(JSON.parse(readFileSync(payerPath, "utf8"))));
umi.use(keypairIdentity(payerKp));

mkdirSync(new URL("./out/", import.meta.url), { recursive: true });
const authPath = new URL(`./out/collection-authority-${cluster}.json`, import.meta.url);
let authKp;
if (existsSync(authPath)) {
  authKp = umi.eddsa.createKeypairFromSecretKey(Uint8Array.from(JSON.parse(readFileSync(authPath, "utf8"))));
} else {
  authKp = umi.eddsa.generateKeypair();
  if (send) {
    writeFileSync(authPath, JSON.stringify(Array.from(authKp.secretKey)));
    chmodSync(authPath, 0o600);
  }
}

const balance = Number((await umi.rpc.getBalance(payerKp.publicKey)).basisPoints) / 1e9;
console.log(JSON.stringify({ cluster, rpc, payer: payerKp.publicKey, payerSol: balance, neededSol: NEEDED_SOL, authority: authKp.publicKey, name: NAME, uri: URI, royaltiesTo: TREASURY, royaltyBps: 500, send }, null, 2));
if (balance < NEEDED_SOL) {
  console.error(`payer has ${balance} SOL; needs at least ${NEEDED_SOL} SOL on ${cluster}. Nothing sent.`);
  process.exit(1);
}
if (!send) {
  console.log("dry run: nothing sent. Add --send to create the collection.");
  process.exit(0);
}

const collection = generateSigner(umi);
const res = await createCollection(umi, {
  collection,
  name: NAME,
  uri: URI,
  updateAuthority: publicKey(authKp.publicKey),
  plugins: [{ type: "Royalties", basisPoints: 500, creators: [{ address: publicKey(TREASURY), percentage: 100 }], ruleSet: ruleSet("None") }],
}).sendAndConfirm(umi, { confirm: { commitment: "confirmed" } });
const signature = bs58.encode(res.signature);
const after = Number((await umi.rpc.getBalance(payerKp.publicKey)).basisPoints) / 1e9;
const out = {
  cluster, collection: collection.publicKey, authority: authKp.publicKey, signature, costSol: +(balance - after).toFixed(9),
  explorer: `https://solscan.io/tx/${signature}${cluster === "devnet" ? "?cluster=devnet" : ""}`,
  next: [
    `cd worker && npx wrangler secret put COLLECTION_AUTHORITY_SECRET --config wrangler.screen.toml   # paste: ${"<base58 of out/collection-authority-" + cluster + ".json, printed by: node print-authority-secret.mjs " + cluster + ">"}`,
    `set [vars] MAINNET_COLLECTION="${collection.publicKey}" and MAINNET_COLLECTION_AUTHORITY="${authKp.publicKey}" in worker/wrangler.screen.toml, then npx wrangler deploy --config worker/wrangler.screen.toml`,
    `app/build.gradle.kts: MAINNET_MINT_READY=true, MAINNET_COLLECTION="${collection.publicKey}", MAINNET_COLLECTION_AUTHORITY="${authKp.publicKey}", MAINNET_PAID_MINT=true; rebuild`,
  ],
};
writeFileSync(new URL(`./out/collection-${cluster}.json`, import.meta.url), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
