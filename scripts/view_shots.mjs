// Checks the interactive design view in a headless browser and takes the screenshots used in the docs.
//
//   node scripts/view_shots.mjs docs/examples/maple-10k-view.html [docs/examples]
//
// The HTML file is what show_design writes (the view with the data embedded). Every tab and 3-D view is opened; any
// script error fails the run. With an output folder, view-rocket.png, view-side.png, view-fly.png and view-flight.png
// are written there. Needs Playwright with Chromium (npm install -g playwright && npx playwright install chromium).
import { execSync } from "node:child_process";
import { resolve } from "node:path";

async function playwright() {
  try {
    return await import("playwright");
  } catch (e) {
    const root = execSync("npm root -g").toString().trim();
    return await import(resolve(root, "playwright/index.mjs"));
  }
}

const [file, outDir] = process.argv.slice(2);
if (!file) {
  console.error("usage: node scripts/view_shots.mjs <view.html> [output folder]");
  process.exit(2);
}
const { chromium } = await playwright();
// SwiftShader: WebGL without a GPU (CI machines, containers).
const browser = await chromium.launch({ args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"] });
const page = await browser.newPage({ viewport: { width: 960, height: 760 }, deviceScaleFactor: 1 });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
const setTime = (t) => page.evaluate((x) => { const e = document.querySelector("#time"); e.value = x; e.dispatchEvent(new Event("input")); }, t);
const shot = async (name) => { if (outDir) await page.screenshot({ path: resolve(outDir, name) }); };
const event = (kind, last) => page.evaluate(([k, l]) => { const ev = window.__orView.data.flight.events.filter((e) => e.kind === k); return (l ? ev.pop() : ev[0] || {}).t; }, [kind, last]);

await page.goto("file://" + resolve(file));
await page.waitForTimeout(1500);
if (await page.$("#app[hidden]")) errors.push("the view did not load its data: " + (await page.textContent("#msg")));

// Rocket: exploded cut-away with the main parachute selected.
await page.check("#cut");
await page.evaluate(() => { const e = document.querySelector("#explode"); e.value = 0.55; e.dispatchEvent(new Event("input")); });
await page.evaluate(() => {
  // The main: the parachute of the last deployment (whatever it is called), else the heaviest chute.
  const d = window.__orView.data, parts = d.rocket.parts, chutes = parts.filter((p) => p.kind === "chute");
  const last = d.flight.events.filter((e) => e.kind === "deploy" && e.branch === 0).pop();
  const grams = (p) => parseFloat(String(p.mass)) * (/ kg/.test(p.mass) ? 1000 : 1);
  const main = chutes.find((p) => last && p.name === last.device) || chutes.sort((a, b) => grams(b) - grams(a))[0] || parts[0];
  document.querySelector('.prow[data-i="' + main.i + '"]').click();
});
await page.waitForTimeout(400);
await shot("view-rocket.png");

// Side view to scale at burnout.
await page.evaluate(() => { const r = document.querySelector('.prow[aria-selected="true"]'); if (r) r.click(); });
await page.evaluate(() => { const e = document.querySelector("#explode"); e.value = 0; e.dispatchEvent(new Event("input")); });
await page.click("[data-view=side]");
await setTime((await event("burnout")) || 0);
await page.waitForTimeout(400);
await shot("view-side.png");

// Replay a few seconds after the last deployment.
await page.click("[data-view=fly]");
await setTime(((await event("deploy", true)) || 10) + 4);
await page.evaluate(() => { const v = window.__orView.viewer; v.cam.faz = -35; v.cam.fel = 8; v.dirty(); });
await page.waitForTimeout(400);
await shot("view-fly.png");

// Plots with the cursor at maximum velocity, then the other tabs.
await page.click("[data-view=\"3d\"]");
await setTime((await event("maxv")) || 0);
await page.click("[data-tab=flight]");
await page.waitForTimeout(500);
await shot("view-flight.png");
for (const tab of ["trajectory", "checks", "model"]) {
  await page.click(`[data-tab=${tab}]`);
  await page.waitForTimeout(300);
}

await browser.close();
if (errors.length) {
  console.error("view errors:\n" + errors.join("\n"));
  process.exit(1);
}
console.log("view ok" + (outDir ? "; screenshots in " + outDir : ""));
