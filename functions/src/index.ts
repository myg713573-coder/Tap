import { randomUUID, createHash } from "node:crypto";
import express, { type NextFunction, type Request, type Response } from "express";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth, type DecodedIdToken } from "firebase-admin/auth";
import { AggregateField, FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { onRequest } from "firebase-functions/v2/https";

if (getApps().length === 0) initializeApp();

const db = getFirestore();
const authService = getAuth();
const app = express();

interface GameConfig {
  coinsPerTap: number;
  energyCost: number;
  maxEnergy: number;
  regenPerMinute: number;
  maxTapsPerRequest: number;
  maxTapsPer10Seconds: number;
  dailyRewards: number[];
  maintenanceMode: boolean;
}

interface TapConfig {
  appName: string;
  game: GameConfig;
}

declare global {
  namespace Express {
    interface Request {
      authUser?: DecodedIdToken;
    }
  }
}

const DEFAULT_CONFIG: TapConfig = {
  appName: "TAP",
  game: {
    coinsPerTap: 1,
    energyCost: 1,
    maxEnergy: 500,
    regenPerMinute: 10,
    maxTapsPerRequest: 20,
    maxTapsPer10Seconds: 50,
    dailyRewards: [100, 150, 200, 250, 300, 400, 700],
    maintenanceMode: false
  }
};

class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function sendError(response: Response, error: unknown): void {
  if (error instanceof ApiError) {
    response.status(error.status).json({ error: error.message });
    return;
  }
  console.error("TAP API request failed", error);
  response.status(500).json({ error: "Internal server error" });
}

function asyncRoute(handler: (request: Request, response: Response) => Promise<unknown>) {
  return (request: Request, response: Response, _next: NextFunction) => {
    void handler(request, response).catch((error: unknown) => sendError(response, error));
  };
}

function newId(): string {
  return randomUUID();
}

function nowIso(): string {
  return new Date().toISOString();
}

function requireBodyString(value: unknown, name: string, min: number, max: number): string {
  if (typeof value !== "string" || value.trim().length < min || value.length > max) {
    throw new ApiError(400, `${name} is invalid`);
  }
  return value.trim();
}

async function loadConfig(): Promise<{ version: number; config: TapConfig }> {
  const snapshot = await db.doc("appConfiguration/published").get();
  if (!snapshot.exists) return { version: 0, config: DEFAULT_CONFIG };
  const data = snapshot.data()!;
  return { version: data.version as number, config: data.config as TapConfig };
}

function validateConfig(value: unknown): value is TapConfig {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const config = value as Partial<TapConfig>;
  const game = config.game;
  return typeof config.appName === "string" && config.appName.trim().length > 0 && config.appName.length <= 48 &&
    !!game && Number.isSafeInteger(game.coinsPerTap) && game.coinsPerTap > 0 && game.coinsPerTap <= 100_000 &&
    Number.isSafeInteger(game.energyCost) && game.energyCost > 0 && game.energyCost <= 1000 &&
    Number.isSafeInteger(game.maxEnergy) && game.maxEnergy >= game.energyCost && game.maxEnergy <= 10_000_000 &&
    Number.isSafeInteger(game.regenPerMinute) && game.regenPerMinute >= 0 && game.regenPerMinute <= 100_000 &&
    Number.isSafeInteger(game.maxTapsPerRequest) && game.maxTapsPerRequest > 0 && game.maxTapsPerRequest <= 100 &&
    Number.isSafeInteger(game.maxTapsPer10Seconds) && game.maxTapsPer10Seconds > 0 && game.maxTapsPer10Seconds <= 100 &&
    Array.isArray(game.dailyRewards) && game.dailyRewards.length > 0 && game.dailyRewards.length <= 30 &&
    game.dailyRewards.every((reward) => Number.isSafeInteger(reward) && reward > 0 && reward <= 10_000_000) &&
    typeof game.maintenanceMode === "boolean";
}

async function authenticate(request: Request, response: Response, next: NextFunction): Promise<void> {
  const token = request.header("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) {
    response.status(401).json({ error: "Authentication required" });
    return;
  }
  try {
    request.authUser = await authService.verifyIdToken(token, true);
    next();
  } catch {
    response.status(401).json({ error: "Authentication required" });
  }
}

function requireAdmin(request: Request): DecodedIdToken {
  if (request.authUser?.admin !== true) throw new ApiError(403, "Administrator access required");
  return request.authUser;
}

function userId(request: Request): string {
  if (!request.authUser) throw new ApiError(401, "Authentication required");
  return request.authUser.uid;
}

function withAuth(handler: (request: Request, response: Response) => Promise<unknown>) {
  return [authenticate, asyncRoute(handler)] as const;
}

function generateReferralCode(uid: string): string {
  return createHash("sha256").update(uid).digest("hex").slice(0, 10).toUpperCase();
}

function periodStarts(now: Date): Record<string, string> {
  const day = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const week = new Date(day);
  week.setUTCDate(week.getUTCDate() - ((week.getUTCDay() + 6) % 7));
  const month = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
  return {
    daily: day.toISOString().slice(0, 10),
    weekly: week.toISOString().slice(0, 10),
    monthly: month.toISOString().slice(0, 10),
    lifetime: "all"
  };
}

function balanceResponse(
  balance: FirebaseFirestore.DocumentData,
  maxEnergy: number,
  regenPerMinute: number
): FirebaseFirestore.DocumentData & { energy: number; energy_updated_at: string } {
  const updatedAt = (balance.energy_updated_at as Timestamp).toMillis();
  const elapsed = Math.max(0, Date.now() - updatedAt);
  const energy = Math.min(maxEnergy, Number(balance.energy) + Math.floor(elapsed * regenPerMinute / 60_000));
  return { ...balance, energy, energy_updated_at: new Date(updatedAt).toISOString() };
}

app.disable("x-powered-by");
app.use(express.json({ limit: "32kb" }));
app.use((request, response, next) => {
  response.setHeader("Access-Control-Allow-Origin", request.header("origin") ?? "*");
  response.setHeader("Vary", "Origin");
  response.setHeader("Access-Control-Allow-Headers", "authorization,content-type,idempotency-key");
  response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
  response.setHeader("Cache-Control", "no-store");
  if (request.method === "OPTIONS") {
    response.status(204).end();
    return;
  }
  next();
});

app.get("/health", (_request, response) => response.json({ status: "ok" }));
app.get("/v1/config", asyncRoute(async (_request, response) => response.json(await loadConfig())));

app.post("/v1/auth/register", ...withAuth(async (request, response) => {
  const uid = userId(request);
  const username = requireBodyString(request.body?.username, "Username", 3, 24);
  if (!/^[A-Za-z0-9_]+$/.test(username)) throw new ApiError(400, "Username must contain only letters, numbers, or underscores");
  const userRef = db.doc(`users/${uid}`);
  const usernameRef = db.doc(`usernames/${username.toLowerCase()}`);
  const balanceRef = db.doc(`userBalances/${uid}`);
  const email = request.authUser?.email ?? "";
  const { config } = await loadConfig();
  await db.runTransaction(async (transaction) => {
    const [user, usernameDoc] = await transaction.getAll(userRef, usernameRef);
    if (user.exists) throw new ApiError(409, "Account profile already exists");
    if (usernameDoc.exists) throw new ApiError(409, "Username is already registered");
    transaction.create(userRef, {
      uid,
      email,
      username,
      referral_code: generateReferralCode(uid),
      status: "active",
      created_at: nowIso(),
      updated_at: nowIso(),
      last_active_at: nowIso()
    });
    transaction.create(usernameRef, { uid });
    transaction.create(balanceRef, {
      available_coins: 0,
      lifetime_earned: 0,
      pending_coins: 0,
      energy: config.game.maxEnergy,
      energy_updated_at: Timestamp.now(),
      regen_per_minute: config.game.regenPerMinute,
      version: 0
    });
  });
  response.status(201).json({ user: { id: uid, username } });
}));

app.post("/v1/auth/login", ...withAuth(async (request, response) => {
  const uid = userId(request);
  const userRef = db.doc(`users/${uid}`);
  const snapshot = await userRef.get();
  if (!snapshot.exists) throw new ApiError(404, "Account profile not found. Create your profile again.");
  const user = snapshot.data()!;
  if (user.status !== "active") throw new ApiError(403, "This account cannot sign in");
  await userRef.update({ last_active_at: nowIso() });
  response.json({ user: { id: uid, username: user.username } });
}));

app.post("/v1/auth/logout", ...withAuth(async (_request, response) => response.json({ ok: true })));

app.get("/v1/me", ...withAuth(async (request, response) => {
  const uid = userId(request);
  const [userSnapshot, balanceSnapshot, { config }] = await Promise.all([
    db.doc(`users/${uid}`).get(),
    db.doc(`userBalances/${uid}`).get(),
    loadConfig()
  ]);
  if (!userSnapshot.exists || !balanceSnapshot.exists) throw new ApiError(404, "Account profile not found");
  const user = userSnapshot.data()!;
  if (user.status !== "active") throw new ApiError(403, "This account cannot perform this action");
  const balance = balanceResponse(balanceSnapshot.data()!, config.game.maxEnergy, config.game.regenPerMinute);
  response.json({
    id: uid,
    email: user.email,
    username: user.username,
    referral_code: user.referral_code,
    status: user.status,
    created_at: user.created_at,
    available_coins: balance.available_coins,
    lifetime_earned: balance.lifetime_earned,
    pending_coins: balance.pending_coins,
    energy: balance.energy,
    energy_updated_at: balance.energy_updated_at,
    version: balance.version,
    role: request.authUser?.admin === true ? "admin" : null
  });
}));

app.get("/v1/transactions", ...withAuth(async (request, response) => {
  const snapshot = await db.collection("transactions").where("user_id", "==", userId(request))
    .orderBy("created_at", "desc").limit(100).get();
  response.json({ items: snapshot.docs.map((document) => document.data()) });
}));

app.post("/v1/taps", ...withAuth(async (request, response) => {
  const uid = userId(request);
  const idempotencyKey = request.header("idempotency-key") ?? "";
  const tapCount = request.body?.tapCount;
  if (!/^[A-Za-z0-9_-]{8,80}$/.test(idempotencyKey) || !Number.isSafeInteger(tapCount) || tapCount < 1) {
    throw new ApiError(400, "Invalid tap request");
  }
  const eventRef = db.doc(`tapEvents/${uid}_${idempotencyKey}`);
  const userRef = db.doc(`users/${uid}`);
  const balanceRef = db.doc(`userBalances/${uid}`);
  const configRef = db.doc("appConfiguration/published");
  const rateRef = db.doc(`tapRateLimits/${uid}`);
  const now = new Date();
  const starts = periodStarts(now);
  const periods = ["daily", "weekly", "monthly", "lifetime"] as const;
  const leaderboardRefs = periods.map((period) => db.doc(`leaderboardRecords/${uid}_${period}_${starts[period]}`));

  const result = await db.runTransaction(async (transaction) => {
    const snapshots = await transaction.getAll(eventRef, userRef, balanceRef, configRef, rateRef, ...leaderboardRefs);
    const [event, user, balance, configSnapshot, rate, ...leaderboards] = snapshots;
    if (event.exists) return { duplicate: true, ...(event.data() as FirebaseFirestore.DocumentData) };
    if (!user.exists || !balance.exists) throw new ApiError(404, "Account balance not found");
    if (user.get("status") !== "active") throw new ApiError(403, "This account cannot tap");
    const config = configSnapshot.exists ? configSnapshot.get("config") as TapConfig : DEFAULT_CONFIG;
    if (config.game.maintenanceMode) throw new ApiError(503, "Tapping is temporarily unavailable");
    if (tapCount > config.game.maxTapsPerRequest) throw new ApiError(400, "Tap batch is too large");

    const currentBalance = balance.data()!;
    const elapsed = Math.max(0, now.getTime() - (currentBalance.energy_updated_at as Timestamp).toMillis());
    const regenerated = Math.min(config.game.maxEnergy,
      Number(currentBalance.energy) + Math.floor(elapsed * config.game.regenPerMinute / 60_000));
    const acceptedTaps = Math.min(tapCount, Math.floor(regenerated / config.game.energyCost));
    if (acceptedTaps < 1) throw new ApiError(409, "Not enough energy");

    const rateData = rate.data();
    const rateWindowStart = rateData?.window_started_at instanceof Timestamp ? rateData.window_started_at.toMillis() : 0;
    const rateCount = now.getTime() - rateWindowStart >= 10_000 ? 0 : Number(rateData?.tap_count ?? 0);
    if (rateCount + acceptedTaps > config.game.maxTapsPer10Seconds) throw new ApiError(429, "Tap rate limit exceeded");

    const coinsAwarded = acceptedTaps * config.game.coinsPerTap;
    const energy = regenerated - acceptedTaps * config.game.energyCost;
    const transactionId = newId();
    const balanceAfter = Number(currentBalance.available_coins) + coinsAwarded;
    transaction.update(balanceRef, {
      available_coins: FieldValue.increment(coinsAwarded),
      lifetime_earned: FieldValue.increment(coinsAwarded),
      energy,
      energy_updated_at: Timestamp.fromDate(now),
      regen_per_minute: config.game.regenPerMinute,
      version: FieldValue.increment(1)
    });
    transaction.set(rateRef, { tap_count: rateCount + acceptedTaps, window_started_at: rateCount === 0 ? Timestamp.fromDate(now) : rateData!.window_started_at });
    transaction.create(eventRef, {
      id: eventRef.id,
      user_id: uid,
      tap_count: acceptedTaps,
      coins_awarded: coinsAwarded,
      energy_used: acceptedTaps * config.game.energyCost,
      idempotency_key: idempotencyKey,
      created_at: now.toISOString()
    });
    transaction.create(db.doc(`transactions/${transactionId}`), {
      id: transactionId,
      user_id: uid,
      kind: "tap",
      coin_delta: coinsAwarded,
      balance_after: balanceAfter,
      reference_id: eventRef.id,
      idempotency_key: idempotencyKey,
      created_at: now.toISOString()
    });
    for (let index = 0; index < leaderboardRefs.length; index += 1) {
      const period = periods[index];
      const previous = leaderboards[index].data();
      transaction.set(leaderboardRefs[index], {
        user_id: uid,
        username: user.get("username"),
        avatar_url: user.get("avatar_url") ?? null,
        period,
        period_start: starts[period],
        eligible_taps: Number(previous?.eligible_taps ?? 0) + acceptedTaps,
        eligible_coins: Number(previous?.eligible_coins ?? 0) + coinsAwarded,
        updated_at: now.toISOString()
      });
    }
    return { tapCount: acceptedTaps, coinsAwarded, energy, balance: balanceAfter };
  });
  response.json(result);
}));

app.get("/v1/tasks", ...withAuth(async (request, response) => {
  const now = nowIso();
  const [tasks, submissions] = await Promise.all([
    db.collection("tasks").where("active", "==", true).get(),
    db.collection("taskSubmissions").where("user_id", "==", userId(request)).get()
  ]);
  const submissionByTask = new Map(submissions.docs.map((document) => [document.get("task_id") as string, document.get("status") as string]));
  const items = tasks.docs.map((document): FirebaseFirestore.DocumentData & { id: string } => ({ id: document.id, ...document.data() }))
    .filter((task) => (!task.starts_at || task.starts_at <= now) && (!task.ends_at || task.ends_at > now))
    .sort((left, right) => String(left.ends_at ?? "9999").localeCompare(String(right.ends_at ?? "9999")))
    .map((task) => ({ ...task, submission_status: submissionByTask.get(task.id) ?? null }));
  response.json({ items });
}));

app.post("/v1/tasks/:taskId/submit", ...withAuth(async (request, response) => {
  const evidence = request.body?.evidence;
  if (!evidence || typeof evidence !== "object" || Array.isArray(evidence) || JSON.stringify(evidence).length > 4000) {
    throw new ApiError(400, "Task evidence is invalid");
  }
  const taskRef = db.doc(`tasks/${request.params.taskId}`);
  const task = await taskRef.get();
  const now = nowIso();
  if (!task.exists || task.get("active") !== true || (task.get("ends_at") && task.get("ends_at") <= now)) {
    throw new ApiError(404, "Task not found or no longer active");
  }
  if (task.get("verification_type") === "automatic") throw new ApiError(400, "This task requires server-side verification");
  const submissionId = `${userId(request)}_${request.params.taskId}`;
  await db.doc(`taskSubmissions/${submissionId}`).create({
    id: submissionId,
    task_id: request.params.taskId,
    user_id: userId(request),
    status: "pending",
    evidence_json: evidence,
    created_at: now
  }).catch((error: unknown) => {
    if ((error as { code?: number }).code === 6) throw new ApiError(409, "A submission already exists for this task");
    throw error;
  });
  response.status(201).json({ id: submissionId, status: "pending" });
}));

app.get("/v1/leaderboard", ...withAuth(async (request, response) => {
  const period = String(request.query.period ?? "daily");
  if (!["daily", "weekly", "monthly", "lifetime"].includes(period)) throw new ApiError(400, "Invalid leaderboard period");
  const start = periodStarts(new Date())[period];
  const snapshot = await db.collection("leaderboardRecords").where("period", "==", period)
    .where("period_start", "==", start).orderBy("eligible_coins", "desc").limit(100).get();
  response.json({ period, items: snapshot.docs.map((document) => ({
    id: document.get("user_id"),
    username: document.get("username"),
    avatar_url: document.get("avatar_url"),
    taps: document.get("eligible_taps"),
    coins: document.get("eligible_coins")
  })) });
}));

app.get("/v1/notifications", ...withAuth(async (request, response) => {
  const uid = userId(request);
  const [personal, global] = await Promise.all([
    db.collection("notifications").where("user_id", "==", uid).get(),
    db.collection("notifications").where("user_id", "==", null).get()
  ]);
  const items = [...personal.docs, ...global.docs].map((document) => document.data())
    .sort((left, right) => String(right.created_at).localeCompare(String(left.created_at))).slice(0, 100);
  response.json({ items });
}));

app.all("/v1/rewards/daily", ...withAuth(async (request, response) => {
  if (request.method !== "GET" && request.method !== "POST") throw new ApiError(405, "Method not allowed");
  const uid = userId(request);
  const today = new Date().toISOString().slice(0, 10);
  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  const checkins = db.collection("users").doc(uid).collection("dailyCheckins");
  const [latestSnapshot, todaySnapshot, { config }] = await Promise.all([
    checkins.orderBy("claimed_on", "desc").limit(1).get(),
    checkins.doc(today).get(),
    loadConfig()
  ]);
  const latest = latestSnapshot.docs[0]?.data();
  const claimedToday = todaySnapshot.exists;
  const nextStreak = latest?.claimed_on === yesterday ? Number(latest.streak_day) + 1 : 1;
  const rewards = config.game.dailyRewards;
  if (request.method === "GET") {
    response.json({ claimedToday, streak: claimedToday ? latest?.streak_day ?? 1 : latest?.claimed_on === yesterday ? latest.streak_day : 0,
      nextReward: rewards[(nextStreak - 1) % rewards.length], rewards });
    return;
  }
  if (claimedToday) throw new ApiError(409, "Today's reward has already been claimed");
  const balanceRef = db.doc(`userBalances/${uid}`);
  const checkinRef = checkins.doc(today);
  const checkinId = checkinRef.id;
  const transactionId = `daily_${uid}_${today}`;
  const ledgerRef = db.doc(`transactions/${transactionId}`);
  const scheduledReward = rewards[(nextStreak - 1) % rewards.length];
  const now = nowIso();
  const reward = await db.runTransaction(async (transaction) => {
    const [balance, checkin, ledger] = await transaction.getAll(balanceRef, checkinRef, ledgerRef);
    if (checkin.exists || ledger.exists) throw new ApiError(409, "Today's reward has already been claimed");
    if (!balance.exists) throw new ApiError(404, "Account balance not found");
    const balanceAfter = Number(balance.get("available_coins")) + scheduledReward;
    transaction.update(balanceRef, {
      available_coins: FieldValue.increment(scheduledReward),
      lifetime_earned: FieldValue.increment(scheduledReward),
      version: FieldValue.increment(1)
    });
    transaction.create(ledgerRef, {
      id: transactionId, user_id: uid, kind: "daily_reward", coin_delta: scheduledReward,
      balance_after: balanceAfter, reference_id: checkinId, idempotency_key: `daily:${today}`, created_at: now
    });
    transaction.create(checkinRef, {
      id: checkinId, user_id: uid, reward_coins: scheduledReward, streak_day: nextStreak,
      claimed_on: today, transaction_id: transactionId, created_at: now
    });
    return { claimedToday: true, streak: nextStreak, reward: scheduledReward };
  });
  response.status(201).json(reward);
}));

app.get("/v1/admin/overview", authenticate, asyncRoute(async (request, response) => {
  requireAdmin(request);
  const cutoff = new Date(Date.now() - 86_400_000).toISOString();
  const [users, activeUsers, taps, awardedCoins, pendingWithdrawals] = await Promise.all([
    db.collection("users").count().get(),
    db.collection("users").where("last_active_at", ">=", cutoff).count().get(),
    db.collection("tapEvents").count().get(),
    db.collection("transactions").where("coin_delta", ">", 0)
      .aggregate({ total: AggregateField.sum("coin_delta") }).get(),
    db.collection("withdrawals").where("status", "in", ["pending", "under_review"]).count().get()
  ]);
  response.json({ total_users: users.data().count, active_users: activeUsers.data().count,
    total_taps: taps.data().count, coins_awarded: awardedCoins.data().total ?? 0,
    pending_withdrawals: pendingWithdrawals.data().count });
}));

app.get("/v1/admin/tasks", authenticate, asyncRoute(async (request, response) => {
  requireAdmin(request);
  const snapshot = await db.collection("tasks").orderBy("created_at", "desc").limit(200).get();
  response.json({ items: snapshot.docs.map((document) => ({ id: document.id, ...document.data() })) });
}));

app.get("/v1/admin/task-submissions", authenticate, asyncRoute(async (request, response) => {
  requireAdmin(request);
  const status = String(request.query.status ?? "pending");
  if (!["pending", "approved", "rejected"].includes(status)) throw new ApiError(400, "Invalid submission status");
  const snapshot = await db.collection("taskSubmissions").where("status", "==", status).orderBy("created_at", "asc").limit(100).get();
  const items = await Promise.all(snapshot.docs.map(async (document) => {
    const submission = document.data();
    const [user, task] = await Promise.all([
      db.doc(`users/${submission.user_id}`).get(),
      db.doc(`tasks/${submission.task_id}`).get()
    ]);
    return { ...submission, username: user.get("username") ?? "Unknown", task_title: task.get("title") ?? "Removed task",
      reward_coins: task.get("reward_coins") ?? 0 };
  }));
  response.json({ items });
}));

app.post("/v1/admin/tasks", authenticate, asyncRoute(async (request, response) => {
  const admin = requireAdmin(request);
  const title = requireBodyString(request.body?.title, "Title", 3, 100);
  const description = requireBodyString(request.body?.description, "Description", 1, 1000);
  const categories = ["daily", "social", "promotional", "challenge", "engagement", "referral"];
  const verificationTypes = ["external", "admin_review"];
  const { category, verificationType, rewardCoins } = request.body ?? {};
  if (!categories.includes(category) || !verificationTypes.includes(verificationType) ||
    !Number.isSafeInteger(rewardCoins) || rewardCoins < 0 || rewardCoins > 10_000_000) throw new ApiError(400, "Task details are invalid");
  const taskRef = db.collection("tasks").doc();
  const task = { id: taskRef.id, title, description, category, verification_type: verificationType,
    reward_coins: rewardCoins, icon_url: typeof request.body.iconUrl === "string" ? request.body.iconUrl : null,
    requirements_json: request.body.requirements ?? {}, ends_at: typeof request.body.endsAt === "string" ? request.body.endsAt : null,
    active: true, created_at: nowIso(), created_by: admin.uid };
  await taskRef.create(task);
  await db.collection("adminActivityLogs").add({ admin_user_id: admin.uid, action: "create_task", target_type: "task", target_id: taskRef.id, created_at: nowIso() });
  response.status(201).json({ id: taskRef.id });
}));

app.put("/v1/admin/tasks/:taskId", authenticate, asyncRoute(async (request, response) => {
  const admin = requireAdmin(request);
  if (typeof request.body?.active !== "boolean") throw new ApiError(400, "Active state is required");
  const taskRef = db.doc(`tasks/${request.params.taskId}`);
  const task = await taskRef.get();
  if (!task.exists) throw new ApiError(404, "Task not found");
  await taskRef.update({ active: request.body.active });
  await db.collection("adminActivityLogs").add({ admin_user_id: admin.uid, action: "set_task_active", target_type: "task",
    target_id: request.params.taskId, details_json: { active: request.body.active }, created_at: nowIso() });
  response.json({ ok: true });
}));

app.post("/v1/admin/task-submissions/:submissionId/review", authenticate, asyncRoute(async (request, response) => {
  const admin = requireAdmin(request);
  const decision = request.body?.status;
  if (decision !== "approved" && decision !== "rejected") throw new ApiError(400, "Review status is invalid");
  const submissionRef = db.doc(`taskSubmissions/${request.params.submissionId}`);
  const submissionSnapshot = await submissionRef.get();
  if (!submissionSnapshot.exists || submissionSnapshot.get("status") !== "pending") throw new ApiError(409, "Submission is not pending review");
  const submission = submissionSnapshot.data()!;
  const taskRef = db.doc(`tasks/${submission.task_id}`);
  const balanceRef = db.doc(`userBalances/${submission.user_id}`);
  const ledgerRef = db.doc(`transactions/task_${submissionSnapshot.id}`);
  const now = nowIso();
  const result = await db.runTransaction(async (transaction) => {
    const [current, task, balance, ledger] = await transaction.getAll(submissionRef, taskRef, balanceRef, ledgerRef);
    if (!current.exists || current.get("status") !== "pending") throw new ApiError(409, "Submission was already reviewed");
    if (decision === "rejected") {
      transaction.update(submissionRef, { status: "rejected", reviewed_by: admin.uid, reviewed_at: now });
      return { status: "rejected" };
    }
    if (!task.exists || !balance.exists || ledger.exists) throw new ApiError(409, "Submission cannot be approved");
    const reward = Number(task.get("reward_coins"));
    const balanceAfter = Number(balance.get("available_coins")) + reward;
    transaction.update(balanceRef, { available_coins: FieldValue.increment(reward), lifetime_earned: FieldValue.increment(reward), version: FieldValue.increment(1) });
    transaction.create(ledgerRef, { id: ledgerRef.id, user_id: submission.user_id, kind: "task", coin_delta: reward,
      balance_after: balanceAfter, reference_id: submissionSnapshot.id, idempotency_key: `task:${submissionSnapshot.id}`, created_at: now });
    transaction.update(submissionRef, { status: "approved", reviewed_by: admin.uid, reviewed_at: now });
    return { status: "approved", rewardCoins: reward };
  });
  await db.collection("adminActivityLogs").add({ admin_user_id: admin.uid, action: `${decision}_task_submission`,
    target_type: "task_submission", target_id: submissionSnapshot.id, created_at: now });
  response.json(result);
}));

app.put("/v1/admin/config", authenticate, asyncRoute(async (request, response) => {
  const admin = requireAdmin(request);
  const config = request.body?.config;
  if (!validateConfig(config) || JSON.stringify(config).length > 20_000) throw new ApiError(400, "Configuration is invalid");
  const configRef = db.doc("appConfiguration/published");
  const result = await db.runTransaction(async (transaction) => {
    const current = await transaction.get(configRef);
    const version = Number(current.get("version") ?? 0) + 1;
    const record = { version, config, published_by: admin.uid, created_at: nowIso() };
    transaction.set(configRef, record);
    transaction.create(db.doc(`configurationVersions/${version}`), record);
    return { version, config };
  });
  await db.collection("adminActivityLogs").add({ admin_user_id: admin.uid, action: "publish_config", target_type: "configuration",
    details_json: { version: result.version }, created_at: nowIso() });
  response.json(result);
}));

app.use((_request, response) => response.status(404).json({ error: "Not found" }));

export const api = onRequest({ region: "us-central1", maxInstances: 20 }, app);