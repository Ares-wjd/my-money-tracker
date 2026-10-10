import { initializeApp, type FirebaseApp, type FirebaseOptions } from 'firebase/app';
import { getAuth, type Auth } from 'firebase/auth';
import { getFirestore, type Firestore } from 'firebase/firestore';

/**
 * Firebase 웹 설정은 저장소에 넣지 않고, 빌드할 때 GitHub Actions 변수 FIREBASE_WEB_CONFIG 로 받는다.
 * Firebase 콘솔이 보여주는 `const firebaseConfig = { apiKey: "...", ... };` 를 그대로 붙여넣어도 되고 JSON 이어도 된다.
 */
export function parseFirebaseConfig(text: string | undefined): FirebaseOptions | null {
  if (!text) return null;
  const config: { [key: string]: string } = {};
  for (const match of text.matchAll(/["']?(\w+)["']?\s*:\s*["']([^"']+)["']/g)) {
    config[match[1]] = match[2];
  }
  return config.apiKey && config.projectId && config.appId ? (config as FirebaseOptions) : null;
}

const options = parseFirebaseConfig(import.meta.env.VITE_FIREBASE_CONFIG);

export interface FirebaseServices {
  app: FirebaseApp;
  auth: Auth;
  db: Firestore;
}

/** 설정이 없으면 null (화면에서 설정 안내를 보여준다). */
export const firebase: FirebaseServices | null = (() => {
  if (!options) return null;
  const app = initializeApp(options);
  return { app, auth: getAuth(app), db: getFirestore(app) };
})();
