import assert from "node:assert/strict";
import { mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const playwrightCore = fileURLToPath(
  new URL("../build/playwright-runtime/node_modules/playwright-core", import.meta.url)
);
const { chromium } = require(playwrightCore);

const baseUrl = process.argv[2] ?? process.env.VEHICLE_STAGE_URL ?? "http://127.0.0.1:8765/index.html";
const reportDir = new URL("../build/reports/vehicle-stage/", import.meta.url);
mkdirSync(reportDir, { recursive: true });

function angularDistance(a, b) {
  const raw = Math.abs(a - b) % 360;
  return Math.min(raw, 360 - raw);
}

async function rotateToYaw(page, targetDegrees) {
  const current = (await page.evaluate(() => window.__icar3dDiagnostics())).yawDegrees;
  const delta = ((targetDegrees - current + 540) % 360) - 180;
  const box = await page.locator("#stage-canvas").boundingBox();
  assert.ok(box, "stage canvas must be visible before selecting a view angle");
  const dragPixels = delta / 0.58;
  const centerX = box.x + box.width * 0.5;
  const y = box.y + box.height * 0.5;
  await page.mouse.move(centerX - dragPixels * 0.5, y);
  await page.mouse.down();
  await page.mouse.move(centerX + dragPixels * 0.5, y, { steps: 12 });
  await page.mouse.up();
}

async function verifyStage(browser, viewport, screenshotName) {
  const page = await browser.newPage({ viewport, deviceScaleFactor: 1 });
  const consoleErrors = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => consoleErrors.push(error.message));

  await page.goto(baseUrl, { waitUntil: "networkidle" });
  await page.waitForFunction(
    () => window.__icar3dDiagnostics?.().loaded === true,
    null,
    { timeout: 20000 }
  );

  const first = await page.evaluate(() => window.__icar3dDiagnostics());
  await page.waitForTimeout(700);
  const second = await page.evaluate(() => window.__icar3dDiagnostics());

  assert.equal(first.modelVariant, "hoperun-education-car", "stage must use the referenced HopeRun car");
  assert.ok(first.partCount >= 80, "model must contain the car's distinct physical details");
  assert.equal(first.detailCounts.wheels, 4, "HopeRun car must have four mecanum wheels");
  assert.ok(first.detailCounts.mecanumRollers >= 40, "mecanum wheels must show diagonal rollers");
  assert.equal(first.detailCounts.sensorModules, 8, "open sensor bay must contain eight modules");
  assert.equal(first.detailCounts.antennas, 2, "upper body must include the two antennas");
  assert.equal(first.detailCounts.headlights, 2, "front grille must include two headlights");
  assert.equal(first.detailCounts.depthCameraLenses, 3, "top depth camera must expose three lenses");
  assert.equal(first.detailCounts.displays, 1, "rear body must include the angled control display");
  assert.equal(first.displayRollDegrees, 0, "display must stay level instead of rolling sideways");
  assert.equal(first.radarSweepStyle, "radial-line", "radar sweep must not render as a solid tail-like sector");
  assert.ok(first.detailCounts.grilleBars >= 9, "front fascia must retain the vertical grille");
  assert.ok(first.modelSize.x > first.modelSize.y, "vehicle must keep the reference car's low profile");
  assert.ok(first.modelSize.z > 0.2, "vehicle must include the full four-wheel width");
  assert.equal(first.modelY, second.modelY, "automatic rotation must not move the model vertically");
  assert.ok(angularDistance(first.yawDegrees, second.yawDegrees) > 10, "yaw must advance automatically");
  assert.ok(first.rendererWidth > 250 && first.rendererHeight > 180, "renderer must fill the stage");

  const canvasDataLength = await page.locator("#stage-canvas").evaluate((canvas) => canvas.toDataURL().length);
  assert.ok(canvasDataLength > 10000, "WebGL canvas must contain rendered pixels");

  const box = await page.locator("#stage-canvas").boundingBox();
  assert.ok(box, "stage canvas must be visible");
  const beforeDrag = second.yawDegrees;
  await page.mouse.move(box.x + box.width * 0.45, box.y + box.height * 0.5);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.72, box.y + box.height * 0.5, { steps: 8 });
  await page.mouse.up();
  const afterDrag = (await page.evaluate(() => window.__icar3dDiagnostics())).yawDegrees;
  assert.ok(angularDistance(beforeDrag, afterDrag) > 20, "horizontal drag must take over the model yaw");

  await page.evaluate(() => window.ICar3DStage.setActive(false));
  const pausedFrames = (await page.evaluate(() => window.__icar3dDiagnostics())).frameCount;
  await page.waitForTimeout(250);
  const framesAfterWait = (await page.evaluate(() => window.__icar3dDiagnostics())).frameCount;
  assert.ok(framesAfterWait - pausedFrames <= 1, "inactive stage must stop rendering frames");

  await page.screenshot({ path: new URL(screenshotName, reportDir).pathname.slice(1) });
  if (screenshotName === "desktop.png") {
    for (const [name, yaw] of [["front.png", 0], ["side.png", 90], ["rear.png", 180]]) {
      await rotateToYaw(page, yaw);
      await page.screenshot({ path: new URL(name, reportDir).pathname.slice(1) });
    }
  }
  assert.deepEqual(consoleErrors, [], `browser console errors: ${consoleErrors.join(" | ")}`);
  await page.close();
}

const browser = await chromium.launch({
  headless: true,
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
});
try {
  await verifyStage(browser, { width: 390, height: 312 }, "mobile.png");
  await verifyStage(browser, { width: 900, height: 420 }, "desktop.png");
  console.log("Vehicle stage browser checks passed");
} finally {
  await browser.close();
}
