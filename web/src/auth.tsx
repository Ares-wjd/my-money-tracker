import { GoogleAuthProvider, onAuthStateChanged, signInWithPopup, signOut, type User } from 'firebase/auth';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { firebase } from './firebase';

interface AuthState {
  /** undefined: 확인 중, null: 로그아웃 상태 */
  user: User | null | undefined;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null | undefined>(undefined);

  useEffect(() => {
    if (!firebase) return;
    return onAuthStateChanged(firebase.auth, setUser);
  }, []);

  const state: AuthState = {
    user,
    // GitHub Pages 주소에서는 팝업 방식이 안정적이다 (리디렉션 방식은 브라우저의 제3자 저장소 차단에 걸릴 수 있음).
    signIn: async () => {
      if (!firebase) return;
      await signInWithPopup(firebase.auth, new GoogleAuthProvider());
    },
    signOut: async () => {
      if (!firebase) return;
      await signOut(firebase.auth);
    },
  };
  return <AuthContext.Provider value={state}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error('AuthProvider 안에서만 쓸 수 있습니다.');
  return state;
}
