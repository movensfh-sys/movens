# movens

> 차량 이상 소리 기록, 저속 페달 오조작 경고, 경고등 음성 안내로 운전 중 불안을 줄여 주는 Pleos Connect 운전 안전 앱

현대자동차그룹 차량용 앱 개발 공모전 출품작 (대학(원)생 부문). 앱을 실행해 두면 별도 설정 없이 동작한다.
원인은 진단하지 않는다. 기록·알림·정비 전달까지만 한다.

## 기능
| 기능 | 동작 | 사용 신호 |
|---|---|---|
| 저속 페달 오조작 경고 | R/D 기어, 15 km/h 이하에서 브레이크 없이 2초 안에 8 km/h 이상 급가속 → 경고음 + "브레이크를 밟으세요". 브레이크를 밟는데도 가속하면 "정차하세요" + 참고 기록 | 기어, 브레이크(Vehicle SDK) · 속도(Car API) · 크루즈(Vehicle SDK, 크루즈 중 미적용) |
| 이상 소리 기록 | 실내 마이크를 1초 단위로 읽어 평소 음량·음색을 기준선으로 학습한다. 벗어난 소리가 나면 5초 동안 다시 들어 보고(재확인), 또 들릴 때만 10초 녹음과 함께 정비 카드를 만든다. 최근 10분 안에 3회째가 되면 음성으로 정비 점검을 권고 | 마이크 · 창문/와이퍼/송풍(열림·작동 중에는 판단 안 함) · 속도/브레이크(어떤 때 났는지 기록) |
| 경고등 안내 | 경고등 22종을 쉬운 이름과 설명으로 읽어 준다. 색이 곧 위험도(빨강 위험 / 노랑 주의 / 초록 안내)이고, 알림은 빨강 10분 · 노랑 5분 · 초록 2분 동안 화면 오른쪽 위에 남는다. 여러 개가 한꺼번에 켜지면 가장 위험한 색 기준으로 한 번에 묶어 안내 | Safety.registerTelltales · Android 알림 |
| 정비 전달 카드 | "언제 · 주행거리 · 종류 · 어떤 때 · 내용 · n회째"를 한 줄로 기록. 차량 안(앱 내부 저장소)에만 저장 | 주행거리(Odometer) |

### 시연 기능 (에뮬레이터)
| 기능 | 동작 |
|---|---|
| 주행 시뮬레이션 | 마이크 대신 합성 엔진 소리를 같은 분석 경로(SoundMonitor → AnomalyDetector)에 넣는다. 처음 20초는 기준선 학습, 이후 12~22초 간격으로 엔진 이상 소리(노킹) · 비(와이퍼 → 소리 판단 중지) · 경고등이 무작위로 일어난다. Virtual Console에 기어 · 속도 · 경고등 · 와이퍼를 보내 계기판 3D 차량도 함께 움직인다 |
| 후진 주차 체험 | 정차 중 화면에서 R 기어로 가속 페달을 한 번씩 눌러 천천히 후진한다. 1.5초 안에 5번 이상 연타하면 페달 오조작 의심 상황으로 보고 경고음과 음성으로 브레이크를 밟도록 안내하고, 속도 · 후방 거리 · 브레이크 반응 시간을 타임라인으로 남긴다 |

화면은 4개 탭(소리 모니터링 · 후진 주차·급발진 · 경고등 안내 · 기록)이다.
`demo_sounds/`에는 마이크 시연용 소리(평소 엔진 30초, 노킹 60초, 평소 25초 → 노킹 합본)가 있다.

## 구조
```
센서  Vehicle SDK(Gear/Brake/DrivingMode/Odometer/Window/Wiper/HVAC/Safety) · Car API(PERF_VEHICLE_SPEED) · AudioRecord
  → 판단  PedalRule.kt(페달 규칙) · Sound.kt(AnomalyDetector 기준선/이상 판단) · Telltales.kt(경고등 22종 사전·알림)
  → 출력  Speaker.kt(Pleos TextToSpeech SDK, ON_DEVICE) · Ui.kt(큰 글씨 화면) · Cards.kt(정비 카드, JSON Lines)
시연   DriveSim.kt(합성 엔진 소리·무작위 사건) · ReverseSim.kt(후진 주차 체험) · DriveSceneView.kt(주행 장면) · VirtualConsole.kt(에뮬레이터 연동)
MainActivity.kt 가 신호를 받아 위 부품에 연결한다.
```
판단 로직은 순수 Kotlin이라 단위 테스트 22개로 검증한다: `app/src/test/` (PedalRuleTest, AnomalyDetectorTest, DriveScenarioTest, ReverseSimModelTest). `./gradlew test`

## 테스트 환경
- Android Studio **Meerkat(2024.3.2) 이상**, Android SDK API 34, JDK 17 이상(Studio 내장 JDK로 충분)
- Pleos Connect Emulator v2.1.0 (API 34) + Pleos Connect Virtual Console
- Gradle 8.14.3 (wrapper 포함), Android Gradle Plugin 8.10.1, Kotlin 2.2.21, minSdk 28
- Pleos SDK: `ai.pleos.playground:Vehicle:2.3.2`, `ai.pleos.playground:TextToSpeech:3.3.1`

## 실행 방법
1. Android Studio → Tools → SDK Manager → SDK Update Sites에 다음을 추가합니다.
   - Name: `Pleos Connect System Image`
   - URL (Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. SDK Platforms → API 34 → **Pleos Connect system image**를 설치하고 Android Studio를 재시작합니다.
3. Device Manager → Create device → Automotive → Pleos Connect v2.1.0 (Cold boot, RAM 4 GB).
4. 이 저장소를 **한글이 없는 경로**(예: `C:\dev\movens`)에 두고 Android Studio → File → Open으로 엽니다.
   - "Please Select Gradle JVM" 창이 뜨면 **Use JVM 21**을 누릅니다.
5. Gradle Sync가 끝나면 실행 대상을 Pleos Connect 에뮬레이터로 고르고 ▶ Run 합니다. 처음 실행할 때 위치·마이크·알림 권한 창에서 모두 **허용**을 누릅니다.
6. 에뮬레이터 ⋮ → Microphone → "Virtual microphone uses host audio input"을 켭니다 (이상 소리 기록용).

**빌드 없이 앱만 설치**: [Releases](../../releases)(또는 zip의 `apk/`)의 APK 파일을 켜진 에뮬레이터 창에 끌어다 놓습니다.

명령줄: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
(명령줄 빌드는 `ANDROID_HOME` 환경변수나 `local.properties`의 `sdk.dir`이 필요하다. Android Studio로 열면 자동으로 만들어진다.)

**"열리지 않는다 / 실행이 안 된다"면**
- Run 했는데 앱 화면이 안 뜨고 내비만 보인다 → 권한 창이 뒤에 숨은 것. 몇 초 기다리거나 하단 바의 movens 아이콘을 다시 누르면 권한 창이 보인다. 모두 **허용**.
- "No module" / "Module not specified" 오류 → Run → Edit Configurations에서 app 구성을 지우고 File → Sync Project with Gradle Files. 자동으로 다시 만들어진다.
- Android Studio가 Meerkat(2024.3.2)보다 오래됐다 → Studio 업데이트. (Help → Check for Updates)
- Gradle Sync 중 "AGP 업그레이드" 안내가 뜨면 **무시**한다. 올리면 빌드 설정이 어긋난다.
- 실행 대상이 Pleos Connect 에뮬레이터가 아니다 → 일반 폰 에뮬레이터에는 설치되지 않는다(Automotive 전용).
- 에뮬레이터가 "Starting up"에서 멈춘다 → `%USERPROFILE%\.android\advancedFeatures.ini`에 `BluetoothEmulation = off` 한 줄을 넣고 다시 켠다.
- "Gradle 8.14.3 is incompatible with the Gradle JVM version 25" → **Use JVM 21** (또는 Settings → Build Tools → Gradle → Gradle JDK를 17~21로).

명령줄 설치: `adb install -r -g app-debug.apk` (`-g`: 권한 창 없이 자동 허용)

## 시연 순서 (Virtual Console)
Virtual Console은 에뮬레이터 안의 8088 포트에서 뜬다: `adb forward tcp:18088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:18088`.
1. **페달 경고**: Gear Selection → R, 창문 Window Position → 0(닫힘). ACCEL 버튼을 길게 눌러 속도를 올린다 → 경고음과 "브레이크를 밟으세요". 정차 후 카드에 "페달 경고 · 가속 중 · REVERSE …"가 남는다.
2. **경고등**: Safety → Telltales → TPMS On → 타이어 공기압 경고를 쉬운 말로 읽어 주고 오른쪽 위에 알림이 뜬다. 경고등 안내 탭의 시뮬레이터 버튼으로 여러 개를 한꺼번에 켤 수도 있다.
3. **이상 소리**: 창문을 닫고 20초 기준선 학습이 끝난 뒤(화면의 "감시 중"), 노트북 근처에서 큰 소리를 내고 5초 안에 한 번 더 낸다(`demo_sounds/`의 노킹 소리를 틀어도 된다) → 재확인 후 카드 "이상 소리 · 정차 중 · 평소 대비 +n dB". 10분 안에 3회째면 음성으로 정비 점검 권고.
4. **오탐 억제**: Wiper → Low, Window → 50, Fan Speed → 5 이상 → 마이크 줄이 "일시 중지"로 바뀐다.
5. **주행 시뮬레이션**: 소리 모니터링 탭 → ▶ 주행 시뮬레이션 시작. 20초 학습 뒤 노킹 · 비 · 경고등이 무작위로 일어난다.
6. **후진 주차 체험**: 후진 주차 탭 → 기어 R → 가속 페달을 한 번씩 눌러 후진 → 1.5초 안에 5번 연타 → 경고가 나오면 브레이크 페달을 누르고 있는다 → 기록 탭에서 타임라인 확인.

Virtual Console 페이지를 새로 열면 차량 상태가 기본값(창문 100 = 열림)으로 돌아가므로, 소리 시연 전에 창문을 0으로 닫는다.

> 주행 중(5 km/h 초과)에는 Pleos 주행 규제로 3rd-party 앱 화면이 가려질 수 있다. movens는 주행 중엔 음성만 쓰고, 화면 안내와 카드는 정차 후에 보인다. 주행 시뮬레이션은 화면이 가려지면 계기판 기어를 P로 두고 속도만 보낸다.

## 알려진 제한 (MVP)
- 기준선 학습은 시연용으로 20초(기획서는 누적 2시간). `AnomalyDetector(learnFrames=)`로 조정.
- 소리 특징은 음량·영교차율·충격음 지표만 쓴다. 오탐이 많으면 FFT 대역 에너지로 올린다.
- 화면이 가려진 동안(주행 중)에도 마이크 분석을 이어 가려면 마이크 유형 포그라운드 서비스가 필요하다(Android는 백그라운드 앱의 마이크 입력을 막는다). 아직 적용하지 않았다.
- 경고등 등록에는 `pleos.car.permission.PRIVATE_CAR_SAFETY`가 필요하다. 에뮬레이터에서는 허용되지만 App Market 심사 시 확인 필요. 거부되면 경고등 기능만 꺼지고 나머지는 동작한다.
- Virtual Console 연동(`INTERNET`, `127.0.0.1:8088` 평문 통신)은 에뮬레이터 시연 전용이다. 실차에는 이 서버가 없어 연결이 안 되면 앱 안 시뮬레이션만 한다.
- 임계값(15 km/h, 8 km/h, 2초, +6 dB 등)은 기획서의 초기값이다.

## APK
`app/build/outputs/apk/debug/app-debug.apk` (디버그 서명). 릴리스는 [GitHub Releases](../../releases)에 올린다.

## 사용 SDK / 권한
| 입력 | 출처 | 권한 |
|---|---|---|
| 기어, 브레이크, 크루즈, 주행거리 | Pleos Vehicle SDK (Gear, Brake, DrivingMode, Odometer) | `pleos.car.permission.CAR_DRIVE` |
| 창문, 와이퍼 | Pleos Vehicle SDK (Window, Wiper) | `pleos.car.permission.CAR_WINDOWS` |
| 송풍 | Pleos Vehicle SDK (HVAC) | `pleos.car.permission.CAR_CLIMATE` |
| 경고등 | Pleos Vehicle SDK (Safety) | `pleos.car.permission.CAR_SAFETY`, `PRIVATE_CAR_SAFETY` |
| 음성 안내 | Pleos TextToSpeech SDK (ON_DEVICE) | `pleos.car.permission.TTS_SERVICE` |
| 차량 속도 | AAOS Car API (`PERF_VEHICLE_SPEED`) | `android.car.permission.CAR_SPEED` |
| 실내 마이크 | Android `AudioRecord` | `android.permission.RECORD_AUDIO` |
| 경고등 알림 | Android 알림 | `android.permission.POST_NOTIFICATIONS` |
| 시연 연동 (에뮬레이터 전용) | Virtual Console REST | `android.permission.INTERNET` |

## 라이선스
[MIT](LICENSE)
