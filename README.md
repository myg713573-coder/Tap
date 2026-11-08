# TAP

TAP is an Android-first coin tapping app using Firebase Authentication, Cloud Firestore, Cloud Functions, and Firebase Hosting. The Firebase Functions API is authoritative for balances and rewards. The Android app requires connectivity for reward-sensitive actions; direct client Firestore reads/writes are denied by the deployed rules.

Implemented flows include Firebase account/profile bootstrap, confirmed taps with energy and rolling per-user throttling, an immutable-style transaction ledger, daily check-ins, task submissions and administrator review, leaderboard aggregation, remote game settings, and a separate admin dashboard. This remains a product foundation, not a production-complete financial rewards application. Withdrawals, booster purchases, referral qualification and payouts, AdMob verification, push delivery, App Check enforcement, account recovery, and a complete user-management console are not implemented. Do not promise or process cash withdrawals from this build.

## Firebase Setup

1. Create a Firebase project and register an Android app with package name `com.tap.app`.
2. Enable Email/Password in Firebase Authentication and create a Cloud Firestore database.
3. Register a Firebase Web app in the same project. Keep its public web configuration available for the admin login form.
4. Install Node.js 22 and Firebase CLI, then select the project from the repository root:

   ```sh
   npm install --prefix functions
   npx --prefix functions firebase-tools use --add
   ```

5. Deploy from the repository root (or use the equivalent `npm run deploy` from `functions/`):

   ```sh
   npm --prefix functions run build
   npx --prefix functions firebase-tools deploy --project YOUR_FIREBASE_PROJECT_ID --only functions,firestore:rules,firestore:indexes,hosting
   ```

6. Add the Firebase Android app config as `app/google-services.json`. It is intentionally ignored by Git. Add the Firebase Web app config JSON in the admin sign-in screen; those Firebase client values are public identifiers, not server credentials.
7. Use the Firebase Hosting URL (`https://YOUR_FIREBASE_PROJECT_ID.web.app`) as `TAP_API_BASE_URL`. Hosting rewrites `/v1/**` and `/health` to the regional `api` Cloud Function.

Firestore direct access is denied for all clients. Functions use the Admin SDK and verify Firebase ID tokens. Do not loosen `firestore.rules` to make the app work; all app reads/writes currently go through the API.

## Provision The First Admin

The admin console requires a Firebase Auth custom claim named `admin`. Sign in to TAP once to create the account, then use an operator credential with Firebase Admin privileges to grant the claim:

```sh
GCLOUD_PROJECT=YOUR_FIREBASE_PROJECT_ID \
GOOGLE_APPLICATION_CREDENTIALS=/secure/path/service-account.json \
  node functions/scripts/set-admin.mjs admin@example.com
```

The service account file must remain outside the repository. The account must sign out and back in after its claim changes. Never grant this claim to ordinary users.

## Android And Codemagic

Local Android builds require JDK 17, Android SDK platform 35, and `app/google-services.json`:

```sh
gradle :app:assembleDebug -PTAP_API_BASE_URL=https://YOUR_FIREBASE_PROJECT_ID.web.app
```

For Codemagic, configure:

- Android signing integration named exactly `upload_keystore`.
- Environment group `tap_release` with `TAP_API_BASE_URL`, `TAP_RELEASE_EMAIL`, `TAP_VERSION_NAME`, and `GOOGLE_SERVICES_JSON_BASE64`.
- Encode the Android config without line wrapping, for example `base64 -w0 app/google-services.json`, then save the result as `GOOGLE_SERVICES_JSON_BASE64` in Codemagic.
- Store the keystore and its passwords using Codemagic signing/environment settings, not repository files.

The workflow creates a signed APK and AAB. Use the signed AAB for Google Play submission. Codemagic, Firebase, Play Console, and AdMob credentials are not included here.

## Emulator And Checks

From the repository root, install Functions dependencies and run the emulator integration test:

```sh
npm install --prefix functions
npm --prefix functions run test:emulator
```

This test starts demo Firebase Auth, Firestore, and Functions emulators and checks authenticated profile creation, taps, duplicate request protection, daily claims, ledger balances, and admin authorization.

To run the emulators interactively, use `npm --prefix functions run serve`. Set a real Firebase project with `firebase use --add` before deployment. Review `firebase.json`, `firestore.rules`, and `firestore.indexes.json` as part of releases.

## Data Model And API

Firestore collections include `users`, `usernames`, `userBalances`, `transactions`, `tapEvents`, `tapRateLimits`, `tasks`, `taskSubmissions`, `leaderboardRecords`, `appConfiguration`, `configurationVersions`, `dailyCheckins` (nested under each user), `notifications`, `withdrawals`, and `adminActivityLogs`.

Current API includes:

- `POST /v1/auth/register` and `POST /v1/auth/login` for authenticated profile bootstrap and validation; Firebase Auth itself handles passwords and sign-in.
- `GET /v1/config`, `GET /v1/me`, and `GET /v1/transactions`.
- `POST /v1/taps`, with Firestore-transaction idempotency, rate limits, energy consumption, balance updates, and leaderboard writes.
- `GET /v1/rewards/daily` and `POST /v1/rewards/daily`.
- `GET /v1/tasks`, `POST /v1/tasks/:id/submit`, and admin task creation, activation, and review routes.
- `GET /v1/leaderboard?period=daily|weekly|monthly|lifetime` and `GET /v1/notifications`.
- Admin overview and validated, versioned configuration publishing.

The admin console is a browser client served by Firebase Hosting. Its preview only covers supported settings; it does not execute remote code or provide an arbitrary page builder.

## Migration Notes

The previous Cloudflare Worker and D1 sources have been replaced. No production Cloudflare deployment or remote D1 data was present in the repository to migrate. Existing real user data, if any exists outside this workspace, needs a separately planned export/import with verified account identity mapping; do not copy password hashes into Firebase Auth.

Before production, implement and test withdrawals, referral and booster ledgers, stronger abuse controls and Firebase App Check, task verification integrations, account recovery, push notifications, AdMob server-side verification, data-retention/privacy controls, and security rules/claims review. The development container has no Android SDK, so an APK cannot be produced here.
