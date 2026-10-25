import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const ROOT = join(fileURLToPath(new URL(".", import.meta.url)), "..");
const GRADLE = join(ROOT, "artifacts/solarchik-handoff/android/app/build.gradle.kts");
const MANIFEST = join(ROOT, "artifacts/solarchik-handoff/android/app/src/main/AndroidManifest.xml");
const STRINGS = join(ROOT, "artifacts/solarchik-handoff/android/app/src/main/res/values/strings.xml");

const RELEASE_CERT_SHA256 = "88486e328bc8779e4982d6d625f17de5ef987b40af0385f34629ab2120e3092b";

function grab(text, re) {
  const match = text.match(re);
  assert.ok(match, `missing ${re}`);
  return match[1];
}

function esc(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function findSdkTool(name) {
  const roots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, "/tmp/android-sdk"].filter(Boolean);
  for (const root of roots) {
    const tools = join(root, "build-tools");
    if (!existsSync(tools)) continue;
    const versions = readdirSync(tools).sort().reverse();
    for (const version of versions) {
      const path = join(tools, version, name);
      if (existsSync(path)) return path;
    }
  }
  return null;
}

function run(bin, args) {
  return execFileSync(bin, args, { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 });
}

import { existsSync as __exists } from "node:fs";
if (!__exists(join(ROOT, "artifacts/solarchik-handoff/android/app/keystore.properties"))) {
  console.log("# skip apk-check: no release keystore on this machine (CI)");
  process.exit(0);
}
const gradle = readFileSync(GRADLE, "utf8");
const applicationId = grab(gradle, /applicationId = "([^"]+)"/);
const versionCode = grab(gradle, /versionCode = (\d+)/);
const versionName = grab(gradle, /versionName = "([^"]+)"/);
const storePassword = grab(
  readFileSync(join(ROOT, "artifacts/solarchik-handoff/android/app/keystore.properties"), "utf8"),
  /storePassword=(.+)/,
);
const apkPath = join(ROOT, `public/Solarchik-CLOCK-IN-${versionName}.apk`);
const handoffApk = join(ROOT, `artifacts/solarchik-handoff/apk/Solarchik-CLOCK-IN-${versionName}.apk`);
const label = grab(readFileSync(STRINGS, "utf8"), /name="app_name">([^<]+)</);
const declaredPerms = [...readFileSync(MANIFEST, "utf8").matchAll(/uses-permission android:name="([^"]+)"/g)].map(
  (match) => match[1],
);

test("release apk matches the android project", () => {
  assert.ok(existsSync(apkPath), `missing ${apkPath}`);
  const aapt = findSdkTool("aapt");
  const apksigner = findSdkTool("apksigner");
  assert.ok(aapt, "aapt not found");
  assert.ok(apksigner, "apksigner not found");

  const zip = run("unzip", ["-t", apkPath]);
  assert.match(zip, /No errors detected/);

  const badging = run(aapt, ["dump", "badging", apkPath]);
  assert.match(badging, new RegExp(`package: name='${esc(applicationId)}' versionCode='${versionCode}' versionName='${esc(versionName)}'`));
  assert.match(badging, new RegExp(`application-label:'${esc(label)}'`));
  assert.match(badging, /launchable-activity: name='net\.solardepin\.solarchik\.MainActivity'/);
  for (const perm of declaredPerms) {
    assert.match(badging, new RegExp(`uses-permission: name='${esc(perm)}'`));
  }

  const xml = run(aapt, ["dump", "xmltree", apkPath, "AndroidManifest.xml"]);
  assert.match(xml, /android:usesCleartextTraffic\([^)]*\)=\(type 0x12\)0x0/);
  assert.match(xml, /android:allowBackup\([^)]*\)=\(type 0x12\)0x0/);
  assert.doesNotMatch(xml, /DeskActivity/);

  const listing = run("unzip", ["-l", apkPath]);
  assert.match(listing, /assets\/sprites\/hero-run-1\.png/);
  assert.match(listing, /assets\/yard-bg\.jpg/);
  assert.doesNotMatch(listing, /assets\/index\.html/);
  assert.doesNotMatch(listing, /native\.js/);

  const signed = run(apksigner, ["verify", "--print-certs", "--verbose", apkPath]);
  assert.match(signed, /^Verifies$/m);
  assert.match(signed, /Verified using v2 scheme \(APK Signature Scheme v2\): true/);
  assert.match(signed, /Verified using v3 scheme \(APK Signature Scheme v3\): true/);
  assert.match(signed, new RegExp(`certificate SHA-256 digest: ${RELEASE_CERT_SHA256}`));

  const apk = readFileSync(apkPath);
  assert.equal(apk.includes(Buffer.from(storePassword)), false, "keystore password leaked into the apk");

  if (existsSync(handoffApk)) {
    assert.equal(readFileSync(handoffApk).equals(apk), true, "handoff apk differs from artifacts apk");
  }
});
