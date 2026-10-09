# Money Tracker

안드로이드용 개인 자산 관리 앱. 데이터는 Google 클라우드(Firebase Firestore)에 저장한다.

## 진행 단계

1. 큰 틀 잡기 (코딩) — 완료
2. 세부 기능 정하기 — 완료 (`docs/FEATURES.md`)
3. **기능 구현하기 (코딩)** ← M1~M6 구현, 테스트·수정 중
4. 디자인 정하기
5. 디자인 반영하기 (코딩)
6. 디버깅 · 기능 추가/수정 · 디자인 수정 반복

## 기술 구성

| 영역 | 사용 기술 |
| --- | --- |
| 언어 / UI | Kotlin, Jetpack Compose (Material 3) |
| 로그인 | Firebase Authentication + Google 로그인 (Credential Manager) |
| 클라우드 저장 | Cloud Firestore (오프라인 캐시 → 온라인 시 자동 동기화) |
| 화면 이동 | Navigation Compose |
| 빌드 / CI | Gradle (Kotlin DSL), GitHub Actions |

최소 지원 버전: Android 8.0 (API 26)

## 프로젝트 구조

```
app/                       안드로이드 앱
  data/AuthRepository      Google 로그인 / 로그아웃
  data/UserDataSource      사용자별 Firestore 저장 공간 (users/{uid})
  ui/AppRoot               로그인 여부에 따라 화면 전환
  ui/login                 로그인 화면
  ui/MainScreen            하단 탭 (요약 / 자산 / 내역 / 설정) - 현재는 임시 화면
  ui/settings              계정 정보, 로그아웃
  ui/theme                 색상 / 테마
core/                      안드로이드와 무관한 순수 Kotlin 로직 (계산, 포맷 등) + 단위 테스트
firestore.rules            Firestore 보안 규칙 (본인 데이터만 접근 가능)
```

## Firebase 설정 (최초 1회)

앱을 실행하려면 본인 Firebase 프로젝트를 연결해야 한다. `google-services.json` 이 없으면 앱이 "Firebase 설정이 필요합니다" 화면만 보여준다.

1. [Firebase 콘솔](https://console.firebase.google.com)에서 프로젝트 생성
2. **Android 앱 추가**
   - 패키지 이름: `com.mymoneytracker.app`
   - SHA-1 인증서 지문 등록 (Google 로그인에 필수). 디버그 키 지문 확인:
     ```
     ./gradlew signingReport
     ```
3. 내려받은 `google-services.json` 을 `app/` 폴더에 넣기 (git 에는 올라가지 않음)
4. **Authentication → 로그인 방법 → Google** 사용 설정
5. **Firestore Database** 생성 후 **규칙** 탭에 `firestore.rules` 내용 붙여넣기
   (규칙 파일이 바뀌면 콘솔에도 다시 붙여넣고 게시해야 한다)

## 폰에 설치하기

GitHub Actions 가 push 할 때마다 테스트와 디버그 APK 빌드를 실행한다.
아래 Secret 3개가 등록되어 있으면 APK 를 **dev-latest 릴리스**로 올리므로, 폰에서 바로 받아 설치할 수 있다.

- 설치 링크: https://github.com/Ares-wjd/my-money-tracker/releases/tag/dev-latest

| Secret 이름 | 내용 |
| --- | --- |
| `GOOGLE_SERVICES_JSON` | `google-services.json` 파일 내용 전체 |
| `SIGNING_KEYSTORE_BASE64` | 고정 서명 키(.jks)를 base64 로 인코딩한 값 |
| `SIGNING_KEYSTORE_PASSWORD` | 서명 키 비밀번호 (별칭은 `mymoneytracker`) |

서명 키는 빌드마다 같아야 한다. 이 키의 SHA-1 을 Firebase 프로젝트 설정 → 내 앱 에 등록해야 Google 로그인이 된다.
서명 키 파일은 저장소에 올리지 않고 따로 보관한다.

### 직접 빌드할 때 (Android Studio)

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk 생성
./gradlew installDebug       # USB 디버깅으로 연결된 폰에 바로 설치
./gradlew test               # 단위 테스트
```

이 경우 PC 의 디버그 키로 서명되므로, 그 키의 SHA-1(`./gradlew signingReport`)도 Firebase 에 추가로 등록해야 한다.
