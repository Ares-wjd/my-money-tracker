// 설치(바탕화면 아이콘)를 위한 최소 서비스 워커. 화면을 저장해 두지 않고 항상 네트워크에서 받아
// 새 버전이 바로 열리게 한다 (데이터는 Firestore 가 따로 처리).
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {
  // 아무것도 가로채지 않는다 (브라우저 기본 동작).
});
