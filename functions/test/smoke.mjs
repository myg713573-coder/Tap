import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";

const projectId = process.env.GCLOUD_PROJECT || "demo-tap";
const functionsUrl = `http://127.0.0.1:5001/${projectId}/us-central1/api`;
const authUrl = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key";

async function call(path, options = {}) {
  const response = await fetch(`${functionsUrl}${path}`, options);
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

const health = await call("/health");
assert.equal(health.response.status, 200);
assert.equal(health.body.status, "ok");

const unauthenticated = await call("/v1/me");
assert.equal(unauthenticated.response.status, 401);

const suffix = randomUUID().replaceAll("-", "").slice(0, 10);
const signUp = await fetch(authUrl, {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ email: `smoke-${suffix}@example.test`, password: "SmokeTestPassword!2026", returnSecureToken: true })
});
const signUpBody = await signUp.text();
assert.equal(signUp.status, 200, signUpBody);
const auth = JSON.parse(signUpBody);
const headers = { authorization: `Bearer ${auth.idToken}`, "content-type": "application/json" };

const profile = await call("/v1/auth/register", {
  method: "POST",
  headers,
  body: JSON.stringify({ username: `smoke_${suffix}` })
});
assert.equal(profile.response.status, 201, JSON.stringify(profile.body));

const idempotencyKey = `smoke${suffix}`;
const tap = await call("/v1/taps", {
  method: "POST",
  headers: { ...headers, "idempotency-key": idempotencyKey },
  body: JSON.stringify({ tapCount: 3 })
});
assert.equal(tap.response.status, 200, JSON.stringify(tap.body));
assert.equal(tap.body.coinsAwarded, 3);

const duplicateTap = await call("/v1/taps", {
  method: "POST",
  headers: { ...headers, "idempotency-key": idempotencyKey },
  body: JSON.stringify({ tapCount: 3 })
});
assert.equal(duplicateTap.body.duplicate, true);

const dailyReward = await call("/v1/rewards/daily", { method: "POST", headers, body: "{}" });
assert.equal(dailyReward.response.status, 201, JSON.stringify(dailyReward.body));
assert.equal(dailyReward.body.reward, 100);
const repeatedReward = await call("/v1/rewards/daily", { method: "POST", headers, body: "{}" });
assert.equal(repeatedReward.response.status, 409);

const currentProfile = await call("/v1/me", { headers });
assert.equal(currentProfile.body.available_coins, 103);
const history = await call("/v1/transactions", { headers });
assert.equal(history.body.items.length, 2);
const adminOnly = await call("/v1/admin/overview", { headers });
assert.equal(adminOnly.response.status, 403);

const adminAuth = getAuth(initializeApp({ projectId }, `test-admin-${suffix}`));
await adminAuth.setCustomUserClaims(auth.localId, { admin: true });
const adminSignIn = await fetch("http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake-api-key", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ email: `smoke-${suffix}@example.test`, password: "SmokeTestPassword!2026", returnSecureToken: true })
});
assert.equal(adminSignIn.status, 200);
const adminSession = await adminSignIn.json();
const adminHeaders = { authorization: `Bearer ${adminSession.idToken}`, "content-type": "application/json" };
const overview = await call("/v1/admin/overview", { headers: adminHeaders });
assert.equal(overview.response.status, 200, JSON.stringify(overview.body));
assert.equal(overview.body.coins_awarded, 103);

const published = await call("/v1/admin/config", {
  method: "PUT",
  headers: adminHeaders,
  body: JSON.stringify({ config: {
    appName: "TAP Test",
    game: { coinsPerTap: 2, energyCost: 1, maxEnergy: 600, regenPerMinute: 12,
      maxTapsPerRequest: 20, maxTapsPer10Seconds: 50, dailyRewards: [100, 150], maintenanceMode: false }
  } })
});
assert.equal(published.response.status, 200, JSON.stringify(published.body));
assert.equal(published.body.version, 1);
const remoteConfig = await call("/v1/config");
assert.equal(remoteConfig.body.config.game.coinsPerTap, 2);

const task = await call("/v1/admin/tasks", {
  method: "POST",
  headers: adminHeaders,
  body: JSON.stringify({ title: "Smoke task", description: "Submit test evidence", category: "daily", rewardCoins: 50, verificationType: "admin_review" })
});
assert.equal(task.response.status, 201, JSON.stringify(task.body));
const submission = await call(`/v1/tasks/${task.body.id}/submit`, {
  method: "POST", headers, body: JSON.stringify({ evidence: { proof: "test-reference" } })
});
assert.equal(submission.response.status, 201);
const pendingReviews = await call("/v1/admin/task-submissions?status=pending", { headers: adminHeaders });
assert.equal(pendingReviews.body.items.length, 1);
const review = await call(`/v1/admin/task-submissions/${submission.body.id}/review`, {
  method: "POST", headers: adminHeaders, body: JSON.stringify({ status: "approved" })
});
assert.equal(review.response.status, 200, JSON.stringify(review.body));
assert.equal(review.body.rewardCoins, 50);
const completedProfile = await call("/v1/me", { headers });
assert.equal(completedProfile.body.available_coins, 153);

console.log("Firebase emulator smoke test passed: Auth, profile, taps, idempotency, daily rewards, ledger, admin claims, config publish, and task review");