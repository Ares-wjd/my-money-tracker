import { describe, expect, it } from 'vitest';
import { parseFirebaseConfig } from './firebase';

describe('Firebase 웹 설정 읽기', () => {
  it('콘솔에 보이는 자바스크립트 형식을 그대로 읽는다', () => {
    const text = `const firebaseConfig = {
      apiKey: "AIzaTest",
      authDomain: "demo.firebaseapp.com",
      projectId: "demo",
      storageBucket: "demo.firebasestorage.app",
      messagingSenderId: "123",
      appId: "1:123:web:abc"
    };`;
    expect(parseFirebaseConfig(text)).toMatchObject({ apiKey: 'AIzaTest', projectId: 'demo', appId: '1:123:web:abc' });
  });

  it('JSON 도 읽고, 필수 값이 없으면 null', () => {
    expect(parseFirebaseConfig('{"apiKey":"k","projectId":"p","appId":"a"}')).toMatchObject({ apiKey: 'k' });
    expect(parseFirebaseConfig('{"apiKey":"k"}')).toBeNull();
    expect(parseFirebaseConfig(undefined)).toBeNull();
  });
});
