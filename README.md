# movens

> 차량 이상 소리 기록, 저속 페달 오조작 경고, 경고등 음성 안내로 운전 중 불안을 줄여 주는 Pleos Connect 운전 안전 앱

현대자동차그룹 차량용 앱 개발 공모전 출품작 (대학(원)생 부문). 시동과 함께 자동으로 동작하며 설정 화면이 없다.

## 기능 (MVP)
| 기능 | 동작 | 사용 신호 |
|---|---|---|
| 저속 페달 오조작 경고 | R/D 기어, 15 km/h 이하에서 브레이크 없이 2초 안에 8 km/h 이상 급가속 → 경고음 + "브레이크를 밟으세요". 브레이크를 밟는데도 가속하면 "정차하세요" + 참고 기록 | 기어, 브레이크(Vehicle SDK) · 속도(Car API) · 크루즈(Vehicle SDK, 크루즈 중 미적용) |
| 이상 소리 기록 | 실내 마이크를 1초 단위로 읽어 평소 음량·음색을 기준선으로 학습하고, 벗어난 소리가 이어지면 10초 클립과 함께 정비 카드 생성. 정차 중 3회 이상 반복되면 음성으로 점검 권고 | 마이크 · 창문/와이퍼/송풍(열림·작동 중에는 판단 안 함) · 속도/브레이크(어떤 때 났는지 기록) |
| 경고등 음성 안내 | 경고등이 켜지면 이름과 위험도(계속 주행 가능 / 가까운 시일 내 점검 / 안전한 곳에 정차 후 확인)를 한 문장으로 안내 | Safety.registerTelltales |
| 정비 전달 카드 | "언제 · 주행거리 · 종류 · 어떤 때 · 내용 · n회째"를 한 줄로 기록. 차량 안(앱 내부 저장소)에만 저장 | 주행거리(Odometer) |

원인은 진단하지 않는다. 기록·알림·정비 전달까지만 한다.

## 구조
```
센서  Vehicle SDK(Gear/Brake/DrivingMode/Odometer/Window/Wiper/HVAC/Safety) · Car API(PERF_VEHICLE_SPEED) · AudioRecord
  → 판단  PedalRule.kt(페달 규칙) · Sound.kt(AnomalyDetector 기준선/이상 판단)
  → 출력  Speaker.kt(Pleos TextToSpeech SDK, ON_DEVICE) · 큰 글씨 화면 · Cards.kt(정비 카드, JSON Lines)
MainActivity.kt 가 신호를 받아 위 세 부품에 연결한다.
```
판단 로직은 순수 Kotlin이라 단위 테스트로 검증한다: `app/src/test/` (PedalRuleTest, AnomalyDetectorTest). `./gradlew test`

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
5. Gradle Sync가 끝나면 실행 대상을 Pleos Connect 에뮬레이터로 고르고 ▶ Run 합니다. 처음 실행할 때 위치·마이크 권한 창에서 **허용**을 두 번 누릅니다.
6. 에뮬레이터 ⋮ → Microphone → "Virtual microphone uses host audio input"을 켭니다 (이상 소리 기록용).

**빌드 없이 앱만 설치**: [Releases](../../releases)(또는 zip의 `apk/`)의 APK 파일을 켜진 에뮬레이터 창에 끌어다 놓습니다.

명령줄: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
(명령줄 빌드는 `ANDROID_HOME` 환경변수나 `local.properties`의 `sdk.dir`이 필요하다. Android Studio로 열면 자동으로 만들어진다.)

**"열리지 않는다 / 실행이 안 된다"면**
- Run 했는데 앱 화면이 안 뜨고 내비만 보인다 → 권한 창(위치·마이크)이 뒤에 숨은 것. 몇 초 기다리거나 하단 바의 movens 아이콘을 다시 누르면 권한 창이 보인다. 두 번 모두 **허용**.
- "No module" / "Module not specified" 오류 → Run → Edit Configurations에서 app 구성을 지우고 File → Sync Project with Gradle Files. 자동으로 다시 만들어진다.
- Android Studio가 Meerkat(2024.3.2)보다 오래됐다 → Studio 업데이트. (Help → Check for Updates)
- Gradle Sync 중 "AGP 업그레이드" 안내가 뜨면 **무시**한다. 올리면 팀원들과 버전이 어긋난다.
- 실행 대상이 Pleos Connect 에뮬레이터가 아니다 → 일반 폰 에뮬레이터에는 설치되지 않는다(Automotive 전용).
- 에뮬레이터가 "Starting up"에서 멈춘다 → `%USERPROFILE%\.android\advancedFeatures.ini`에 `BluetoothEmulation = off` 한 줄을 넣고 다시 켠다.
- "Gradle 8.14.3 is incompatible with the Gradle JVM version 25" → **Use JVM 21** (또는 Settings → Build Tools → Gradle → Gradle JDK를 17~21로).
- GitHub에서 404 → 저장소가 비공개라 `movensfh-sys` 계정으로 로그인해야 보인다. git은 Windows 자격 증명 관리자에서 `git:https://github.com` 항목을 지우고 다시 로그인.

명령줄 설치: `adb install -r -g app-debug.apk` (`-g`: 권한 창 없이 자동 허용)

## 시연 순서 (Virtual Console)
Virtual Console은 에뮬레이터 안의 8088 포트에서 뜬다: `adb forward tcp:18088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:18088`.
1. **페달 경고**: Gear Selection → R, 창문 Window Position → 0(닫힘). ACCEL 버튼을 길게 눌러 속도를 올린다 → 경고음과 "브레이크를 밟으세요". 정차 후 카드에 "페달 경고 · 가속 중 · REVERSE …"가 남는다.
2. **경고등**: Safety → Telltales → TPMS On → "타이어 공기압 경고입니다. 가까운 시일 내 점검이 필요합니다."
3. **이상 소리**: 창문을 닫고 20초 기준선 학습이 끝난 뒤(화면의 "감시 중"), 노트북 근처에서 큰 소리를 2초 이상 낸다 → 카드 "이상 소리 · 정차 중 · 평소 대비 +n dB". 정차 중 3회 반복되면 음성 점검 권고.
4. **오탐 억제**: Wiper → Low, Window → 50, Fan Speed → 5 이상 → 마이크 줄이 "일시 중지"로 바뀐다.

Virtual Console 페이지를 새로 열면 차량 상태가 기본값(창문 100 = 열림)으로 돌아가므로, 소리 시연 전에 창문을 0으로 닫는다.

> 주행 중(5 km/h 초과)에는 Pleos 주행 규제로 모든 3rd-party 앱 화면이 가려진다. movens는 설계대로 주행 중엔 음성만 쓰고, 화면 안내와 카드는 정차 후에 보인다. 앱 프로세스는 계속 동작한다.

## 알려진 제한 (MVP)
- 기준선 학습은 시연용으로 20초(기획서는 누적 2시간). `AnomalyDetector(learnFrames=)`로 조정.
- 소리 특징은 음량·영교차율·충격음 지표만 쓴다. 오탐이 많으면 FFT 대역 에너지로 올린다.
- 경고등 등록에는 `pleos.car.permission.PRIVATE_CAR_SAFETY`가 필요하다. 에뮬레이터에서는 허용되지만 App Market 심사 시 확인 필요. 거부되면 경고등 기능만 꺼지고 나머지는 동작한다.
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

## 라이선스
[MIT](LICENSE)
