import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";

const email = process.argv[2]?.trim().toLowerCase();
const projectId = process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT || process.env.FIREBASE_PROJECT_ID;
if (!email) {
  console.error("Usage: node functions/scripts/set-admin.mjs admin@example.com");
  process.exit(2);
}
if (!projectId) {
  console.error("Set GCLOUD_PROJECT to the Firebase project ID.");
  process.exit(2);
}

if (getApps().length === 0) initializeApp({ projectId });
const auth = getAuth();
const user = await auth.getUserByEmail(email);
await auth.setCustomUserClaims(user.uid, { ...user.customClaims, admin: true });
console.log(`Granted admin claim to ${email} (${user.uid}). The user must sign in again.`);
