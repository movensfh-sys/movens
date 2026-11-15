package ai.movens.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.Manifest
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * movens 메인 화면. 한 번 실행해 두면 별도 설정 없이 동작한다.
 *  센서(Vehicle SDK, Car API, 마이크) → 상황 판단 → 기능 엔진(AnomalyDetector, PedalRule/후진 시뮬레이터, 경고등 안내)
 *  → 출력(음성·경고음, 큰 글씨 화면, 휴대폰식 알림, 정비 카드·급발진 경험 기록)
 *
 * 탭: ① 소리 모니터링  ② 후진 주차·급발진  ③ 경고등 안내  ④ 기록
 */
class MainActivity : Activity() {

    // ---- 차량 신호 ----
    private var gear: VehicleGear? = null
    private var brake = false
    private var speedKmh = 0f             // PERF_VEHICLE_SPEED (차량 신호). Virtual Console의 ACCEL/BRAKE로 바뀐다.
    private var cruise = false
    private var odometerKm: Int? = null
    private var windowOpen = false; private var wiperOn = false; private var fanHigh = false
    private var lastSpeed = 0f; private var lastSpeedAt = 0L; private var accelKmhPerS = 0f
    private val litTelltales = HashSet<TelltaleType>()
    @Volatile private var lastFrame: SoundFrame? = null   // 마지막 1초 프레임
    @Volatile private var micError: String? = null        // 마이크를 못 쓰는 이유 (없으면 null)
    @Volatile private var muteUntil = 0L                   // 경고음·음성 안내가 마이크로 다시 들어가 오탐되지 않도록 잠시 판단 중지
    private var lastAnomalyAt = 0L
    private var lastConfirmedAt = 0L
    // 재확인: 이상 의심이 나면 CONFIRM_MS 동안 다시 들어 보고, 다시 들린 횟수가 CONFIRM_HITS 이상일 때만 기록·알림
    private var pending: Pair<String, ShortArray>? = null
    private var pendingUntil = 0L
    private var pendingHits = 0
    private val history = ArrayDeque<Pair<Double, Boolean>>()   // 최근 60초 (음량, 이상 여부). UI 스레드에서만 다룬다.
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private val wavePeaks = HashMap<String, FloatArray>()

    // ---- 부품 ----
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private lateinit var speaker: Speaker
    private lateinit var store: CardStore
    private lateinit var suaStore: SuaStore
    private lateinit var telltales: TelltaleCenter
    private val pedal = PedalRule()
    private val detector = AnomalyDetector()
    private lateinit var sound: SoundMonitor
    private val sim = ReverseSimModel()
    // 주행 시뮬레이션 (랜덤 엔진 이상 소리 + 랜덤 경고등)
    private val scenario = DriveScenario()
    private var synth: EngineSynth? = null
    private var simOut: AudioTrack? = null
    private var simPlayAudio = true
    private var realSpeed = 0f            // 차량 신호(Virtual Console) 속도
    private var simBraking = false
    private val driveLog = ArrayDeque<String>()
    private val braking get() = brake || simBraking
    // Virtual Console: 왼쪽 클러스터의 3D 차량을 실제로 움직인다 (에뮬레이터 시연용)
    private val vc = VirtualConsole()
    private var vcGear = "Drive"                       // 운전 중 화면 제한에 걸리면 "Park"로 바꿔 속도만 보낸다
    private val vcEchoUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()   // 우리가 켠 경고등이 되돌아온 신호는 무시
    private val vcLit = HashSet<String>()
    private var resumed = false
    private var localRain = false
    private val driveVC get() = scenario.running && vc.connected == true
    private val beeper = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 80) }.getOrNull()
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { checkPending(); driveTick(); evaluatePedal(); updateSuppression(); render(); telltales.tick(); ui.postDelayed(this, 500) }
    }
    private var lastSimTick = 0L
    private val simTicker = object : Runnable { override fun run() { stepSim(); ui.postDelayed(this, 50) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CardStore(this)
        suaStore = SuaStore(this)
        speaker = Speaker(this)
        telltales = TelltaleCenter(this, speaker) { spec ->
            store.add("경고등", condition(), "${spec.name} · ${spec.severity.label}", odometerKm)
            refreshCards()
        }
        sound = SoundMonitor(detector, ::onSoundFrame, ::onSoundAnomaly)
        buildUi()
        vehicle = Vehicle(this).also { it.initialize() }
        registerVehicleSignals()
        vc.probe()

        val wanted = arrayListOf(Car.PERMISSION_SPEED, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startPermissionedInputs() else {
            requestPermissions(missing.toTypedArray(), 1)
            // Pleos 홈은 권한 창이 뜨면 앱 패널을 숨긴다. 우리 태스크를 다시 앞으로 가져오면 권한 창이 보인다.
            ui.postDelayed({ startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, 1_500)
        }
        ui.post(ticker)
        lastSimTick = System.currentTimeMillis()
        ui.post(simTicker)
        refreshCards()
        refreshSua()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = startPermissionedInputs()

    // =====================================================================
    // 입력
    // =====================================================================

    /** 등록이 거부된 신호(권한 없음 등)는 그 기능만 끄고 나머지는 계속 동작한다. */
    private fun registerVehicleSignals() {
        signal("기어") { vehicle.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) { gear = g; evaluatePedal() }
            override fun onFailed(e: Exception) = fail("기어", e)
        }) }
        signal("브레이크") { vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) { brake = pressed == true; evaluatePedal() }
            override fun onFailed(e: Exception) = fail("브레이크", e)
        }) }
        signal("크루즈") { vehicle.getDrivingMode().registerCruiseControlState(object : CruiseControlStateListener {
            override fun onCruiseControlStateUpdated(s: CruiseControl?) { cruise = s == CruiseControl.ACTIVATED }
            override fun onFailed(e: Exception) = fail("크루즈", e)
        }) }
        signal("주행거리") { vehicle.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) { odometerKm = km?.toInt() }
            override fun onFailed(e: Exception) = fail("주행거리", e)
        }) }
        signal("창문") { vehicle.getWindow().registerWindowPosition(WindowArea(WindowValue.ROW_1_LEFT), object : WindowPositionListener {
            override fun onWindowPositionUpdated(pos: Int?) { windowOpen = (pos ?: 0) > 0; updateSuppression() }
            override fun onFailed(e: Exception) = fail("창문", e)
        }) }
        signal("와이퍼") { vehicle.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), object : WiperStateListener {
            override fun onWiperStateUpdated(s: WiperState?) { wiperOn = s == WiperState.ON; updateSuppression() }
            override fun onFailed(e: Exception) = fail("와이퍼", e)
        }) }
        // 에뮬레이터의 송풍 값은 앞줄 전체(areaId 5 = ROW_1_LEFT|ROW_1_RIGHT)로 온다. 한 좌석만 등록하면 받지 못한다.
        signal("송풍") { vehicle.getHVAC().registerHvacFanSpeed(SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT), object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(level: Int?) { fanHigh = (level ?: 0) >= 5; updateSuppression() }
            override fun onFailed(e: Exception) = fail("송풍", e)
        }) }
        signal("경고등") { vehicle.getSafety().registerTelltales(object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(t: Telltales?) { t?.let(::onTelltales) }
            override fun onFailed(e: Exception) = fail("경고등", e)
        }) }
    }

    private val disabledSignals = ArrayList<String>()
    private fun signal(what: String, register: () -> Unit) {
        try { register() } catch (e: Exception) { disabledSignals += what; fail(what, e) }
    }

    private fun startPermissionedInputs() {
        if (car == null && checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            try {
                car = Car.createCar(this)
                val pm = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(v: CarPropertyValue<*>) {
                        val kmh = (v.value as Float) * 3.6f
                        realSpeed = kmh
                        if (!scenario.running || (!driveVC && kmh > 1f)) setSpeed(kmh)   // 시뮬레이션이 콘솔을 몰고 있으면 그 속도를 그대로 씀
                    }
                    override fun onErrorEvent(propId: Int, zone: Int) { Log.w("movens", "speed error $propId") }
                }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            } catch (e: Exception) { fail("속도", e) }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micError = null
            sound.start { micError = it }
        } else micError = "마이크 권한 없음"
    }

    private fun setSpeed(kmh: Float) {
        speedKmh = kmh
        val now = System.currentTimeMillis()
        if (lastSpeedAt > 0 && now > lastSpeedAt) accelKmhPerS = (speedKmh - lastSpeed) / ((now - lastSpeedAt) / 1000f)
        lastSpeed = speedKmh; lastSpeedAt = now
        evaluatePedal()
    }

    /** 창문·와이퍼·송풍, 녹음 재생 중, 경고음 직후에는 소리 판단을 멈춘다(오탐 억제). */
    private fun updateSuppression() {
        // 합성 엔진 소리(주행 시뮬레이션)는 스피커 소리가 마이크로 되돌아오지 않으므로 재생·안내음 때 멈출 필요가 없다.
        sound.suppressed = windowOpen || wiperOn || localRain || fanHigh ||
            (synth == null && (player != null || System.currentTimeMillis() < muteUntil))
    }

    private fun fail(what: String, e: Exception) { Log.w("movens", "$what 실패: ${e.message}") }

    // =====================================================================
    // 판단
    // =====================================================================

    /** 지금 주행 상황을 한 단어로. 정비 카드의 "어떤 때 났는지"에 쓴다. */
    private fun condition(): String = when {
        speedKmh < 1f -> "정차 중"
        braking -> "브레이크 밟을 때"
        accelKmhPerS > 2f -> "가속 중"
        else -> "정속 주행"
    }

    /** 실제 차량 신호(Virtual Console)로 하는 저속 페달 오조작 판단. */
    private fun evaluatePedal() {
        val gearRD = gear == VehicleGear.GEAR_REVERSE || gear == VehicleGear.GEAR_DRIVE
        val a = pedal.update(System.currentTimeMillis(), gearRD, speedKmh, brake, cruise) ?: return
        muteUntil = System.currentTimeMillis() + 5_000
        speaker.alarm(a.speech)
        showAlert(Level.DANGER, a.speech, "저속 구간 페달 오조작이 의심됩니다 · 참고 기록을 남겼습니다")
        val detail = "${gear?.name?.removePrefix("GEAR_")} ${speedKmh.toInt()}km/h, 브레이크 ${if (brake) "밟음" else "안 밟음"}"
        store.add("페달 경고", condition(), detail, odometerKm)
        refreshCards()
    }

    /** 오디오 스레드에서 호출된다. */
    private fun onSoundFrame(f: SoundFrame) {
        lastFrame = f
        ui.post {
            history.addLast(f.dbfs to false); while (history.size > HISTORY_SEC) history.removeFirst()
            if (pending != null) {
                // 재확인 창 안에서 충격음이나 경고선을 넘는 소리가 다시 들리면 센다
                if (f.crest >= 8 || (!detector.learning && f.dbfs > detector.threshold)) {
                    pendingHits++
                    if (history.isNotEmpty()) history.addLast(history.removeLast().first to true)
                }
                checkPending()
            }
        }
    }

    private fun checkPending() {
        val p = pending ?: return
        if (System.currentTimeMillis() < pendingUntil && pendingHits < CONFIRM_HITS + 1) return
        pending = null
        if (pendingHits >= CONFIRM_HITS) confirmAnomaly(p.first, p.second)
        else {
            showAlert(Level.INFO, "재확인 결과: 일시적인 소리였습니다", "다시 들어 보니 반복되지 않아 기록하지 않았습니다 (다시 들린 횟수 $pendingHits)")
            if (scenario.running) logDrive("재확인: 다시 들리지 않음 → 일시적인 소리로 판단, 기록 안 함")
        }
    }

    /**
     * 오디오 스레드에서 호출된다. 10초 클립을 저장하고 정비 카드를 만든다.
     * 최근 10분 안에 이상 소리가 3번 이상이면 경고음 + 음성 + 닫을 때까지 남는 경고로 정비 점검을 안내한다.
     */
    private fun onSoundAnomaly(reason: String, clip: ShortArray) {
        ui.post {
            if (pending != null) return@post
            val now = System.currentTimeMillis()
            lastAnomalyAt = now
            if (history.isNotEmpty()) history.addLast(history.removeLast().first to true)   // 그래프에 빨간 표시
            pending = reason to clip; pendingUntil = now + CONFIRM_MS; pendingHits = 0
            beeper?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            showAlert(Level.CAUTION, "평소와 다른 엔진 소리 감지 → 다시 확인하는 중",
                "$reason · ${CONFIRM_MS / 1000}초 동안 다시 들어 보고, 또 들리면 기록하고 알려 드립니다")
            if (scenario.running) logDrive("감지: $reason → ${CONFIRM_MS / 1000}초 재확인 시작")
        }
    }

    /** 재확인까지 통과한 이상 소리: 10초 녹음 저장 + 정비 카드 + 알림. 최근 10분 3회째면 정비 점검 경고. */
    private fun confirmAnomaly(reason: String, clip: ShortArray) {
        val path = saveWav(clip)
        run {
            lastConfirmedAt = System.currentTimeMillis()
            val card = store.add("이상 소리", condition(), reason, odometerKm, path, windowMs = REPEAT_WINDOW_MS)
            refreshCards()
            val repeated = card.count >= 3
            telltales.show(TelltaleSpec("ENGINE_SOUND",
                if (repeated) "엔진 소리 반복 · 정비 점검 필요" else "평소와 다른 엔진 소리 (${card.count}회째)",
                if (repeated) "최근 10분 동안 평소와 다른 소리가 ${card.count}번 녹음됐어요. 정비소에서 녹음을 들려주세요."
                else "${card.condition}에 평소와 다른 소리가 녹음됐어요 ($reason). '기록' 탭에서 다시 들을 수 있어요.",
                if (repeated) Severity.RED_LV else Severity.YELLOW_LV, Glyph.SOUND), source = "엔진 소리 안내", log = false, sound = false)
            if (scenario.running) logDrive("재확인됨 → 엔진 이상 소리 기록 ${card.count}회째" + if (repeated) " · 정비 점검 경고" else "")
            if (card.count >= 3) {
                muteUntil = System.currentTimeMillis() + 6_000
                speaker.alarm("엔진에서 평소와 다른 소리가 반복되었습니다. 정비 점검을 받으세요.")
                showAlert(Level.DANGER, "평소와 다른 엔진 소리가 ${card.count}번 반복되었습니다",
                    "정비 점검을 받으세요 · 녹음된 소리는 아래에서 다시 들을 수 있습니다  (누르면 닫힘)", sticky = true)
            } else {
                muteUntil = System.currentTimeMillis() + 1_500
                beeper?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                showAlert(Level.CAUTION, "재확인됨: 평소와 다른 엔진 소리 (${card.count}회째)",
                    "${card.condition} · $reason · 다시 확인 중 ${pendingHits}번 더 들림 · 3회 반복되면 정비 점검을 안내합니다")
            }
        }
    }

    /** 실제 차량 경고등 (Safety SDK). 목록에 있는 것은 쉬운 설명으로, 없는 것은 SDK가 준 색으로 안내한다. */
    private fun onTelltales(t: Telltales) {
        for (d in t.telltales) {
            val type = d.type ?: continue
            val on = d.state == TelltaleState.ON || d.state == TelltaleState.BLINK
            if (!on) { litTelltales.remove(type); continue }
            val echo = vcEchoUntil[type.name]
            if (echo != null && System.currentTimeMillis() < echo) { litTelltales.add(type); continue }   // 앱이 콘솔로 켠 경고등
            if (!litTelltales.add(type)) continue   // 이미 안내한 경고
            // 목록에 없는 경고등은 "주의(노랑)"로 일반 안내한다 (SDK의 색 변환 함수는 외부에 공개되지 않음).
            val spec = TelltaleCatalog.byKey(type.name)
                ?: TelltaleSpec(type.name, "경고등 (${type.name})", "계기판에 경고등이 켜졌어요.", Severity.YELLOW_LV, Glyph.WARNING)
            ui.post { telltales.show(spec) }
        }
    }

    // =====================================================================
    // 주행 시뮬레이션: 합성 엔진 소리로 달리면서 무작위로 엔진 이상 소리·경고등을 만든다
    // =====================================================================

    private fun driveTick() {
        if (!scenario.running) return
        val now = System.currentTimeMillis()
        if (driveVC || realSpeed <= 1f) {
            val (sp, br) = scenario.profile(now); simBraking = br; setSpeed(sp)
            if (driveVC) { val (acc, brk) = scenario.pedals(now); vc.controls(sp, acc, brk) }   // 왼쪽 3D 차량도 같은 속도로
        } else simBraking = false
        synth?.speedKmh = speedKmh
        synth?.knocking = scenario.knocking(now)
        if (localRain && !scenario.raining(now)) endRain()
        when (scenario.tick(now)) {
            DriveScenario.Event.ENGINE_SOUND -> logDrive("무작위 사건: 엔진에서 '딱딱' 이상 소리 (${scenario.knockMs / 1000}초)")
            DriveScenario.Event.TELLTALE -> randomTelltale()
            DriveScenario.Event.RAIN -> startRain()
            null -> {}
        }
    }

    private fun toggleDrive() { if (scenario.running) stopDrive() else startDrive() }

    private fun startRain() {
        localRain = true
        if (vc.connected == true) vc.wiper("Low")
        logDrive("무작위 사건: 비 → 와이퍼 작동 (${scenario.rainMs / 1000}초) · 와이퍼 소리 오탐을 막으려고 소리 판단을 잠시 멈춤")
    }

    private fun endRain() {
        localRain = false
        if (vc.connected == true) vc.wiper("Off")
        logDrive("비 그침 → 와이퍼 끔 · 소리 판단 다시 시작")
    }

    /** 경고등 알림을 띄우고, 콘솔이 연결돼 있으면 왼쪽 계기판에도 실제로 켠다 (1분 뒤 끔). */
    private fun fireTelltale(spec: TelltaleSpec, announce: Boolean = true) {
        telltales.show(spec, sound = announce)
        val type = VirtualConsole.telltaleType(spec.key)
        if (vc.connected == true && type != null) {
            val color = VirtualConsole.color(spec.severity)
            vcEchoUntil[spec.key] = System.currentTimeMillis() + 15_000
            vc.telltale(type, true, color); vcLit += type
            ui.postDelayed({ if (type in vcLit) { vc.telltale(type, false, color); vcLit -= type } }, 60_000)
        }
    }

    override fun onResume() { super.onResume(); resumed = true }

    override fun onPause() {
        super.onPause(); resumed = false
        // 기어 D로 달리면 시스템이 '운전 중 사용 제한' 화면으로 앱을 가릴 수 있다 → 기어 P로 바꾸고 속도만 계속 보낸다.
        if (driveVC && vcGear == "Drive") ui.postDelayed({
            if (!resumed && driveVC && vcGear == "Drive") {
                vcGear = "Park"; vc.gear("Park")
                logDrive("운전 중 화면 제한이 걸려 계기판 기어를 P로 두고 속도만 보냅니다")
            }
        }, 1_200)
    }

    private fun startDrive() {
        val now = System.currentTimeMillis()
        scenario.start(now)
        synth = EngineSynth(seed = now.toInt())
        if (simPlayAudio) simOut = createTrack()
        sound.out = simOut; sound.synth = synth; sound.requestReset()
        vc.probe(); vc.prepareDrive(vcGear)                 // 콘솔이 있으면 프렁크·트렁크를 닫고 왼쪽 3D 차량을 바로 출발시킨다
        sound.start { micError = it }                       // 마이크 권한이 없어도 합성 소리는 분석한다
        history.clear(); lastAnomalyAt = 0L
        driveLog.clear()
        logDrive("주행 시작 · 처음 ${scenario.learnMs / 1000}초는 평소 엔진 소리를 배웁니다")
        updateDriveButtons()
    }

    private fun stopDrive() {
        val wasVC = driveVC
        scenario.stop()
        if (wasVC) {
            vc.park()
            for (t in vcLit) vc.telltale(t, false, "Red"); vcLit.clear()
        }
        if (localRain) endRain()
        sound.synth = null; sound.out = null; synth = null
        releaseTrack()
        simBraking = false
        if (realSpeed <= 1f) setSpeed(0f)
        sound.requestReset()                                 // 실제 마이크 소리로 기준선을 다시 배운다
        history.clear()
        logDrive("주행 종료")
        updateDriveButtons()
    }

    private fun createTrack(): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(16_000 * 2 * 3 / 2)        // 1.5초: 1초씩 써 넣으면 실제 재생 속도에 맞춰 진행된다
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }.getOrNull()

    private fun releaseTrack() {
        val t = simOut ?: return
        simOut = null; sound.out = null
        runCatching { t.pause(); t.flush() }
        ui.postDelayed({ runCatching { t.stop() }; t.release() }, 1_500)   // 오디오 스레드가 쓰기를 마칠 시간
    }

    private fun toggleDriveAudio() {
        simPlayAudio = !simPlayAudio
        if (scenario.running) {
            if (simPlayAudio) { simOut = createTrack(); sound.out = simOut } else releaseTrack()
        }
        updateDriveButtons()
    }

    private fun forceKnock(shortMs: Long? = null) {
        val now = System.currentTimeMillis()
        if (!scenario.running) { logDrive("먼저 주행 시뮬레이션을 시작하세요"); return }
        if (scenario.learning(now) || detector.learning) { logDrive("평소 엔진 소리를 배우는 중이에요. 학습이 끝난 뒤에 눌러 주세요"); return }
        scenario.forceKnock(now, shortMs ?: scenario.knockMs)
        logDrive(if (shortMs != null) "직접 만든 사건: 짧은 '딱' 소리 (${shortMs / 1000}초) → 재확인에서 걸러지는지 보기"
                 else "직접 만든 사건: 엔진에서 '딱딱' 이상 소리 (${scenario.knockMs / 1000}초)")
    }

    /** 무작위 경고등 2~3개를 동시에 켠다. 알림은 위험한 것이 맨 위, 음성은 한 번에 묶어서 안내. */
    private fun randomTelltale() {
        val lit = telltales.active.map { it.spec.key }.toSet()
        val consoleOk = vc.connected == true
        val pool = TelltaleCatalog.all.filter { it.key !in lit && (!consoleOk || VirtualConsole.telltaleType(it.key) != null) }
            .ifEmpty { TelltaleCatalog.all }
        val picks = pool.shuffled().take((2..3).random()).sortedBy { it.severity.ordinal }   // RED → YELLOW → GREEN
        for (spec in picks.reversed()) fireTelltale(spec, announce = false)                // 위험한 것을 마지막에 → 맨 위
        telltales.announce(picks)
        logDrive("무작위 사건: 경고등 ${picks.size}개 동시에 켜짐 — " + picks.joinToString(", ") { it.name })
    }

    private fun logDrive(msg: String) {
        driveLog.addFirst(SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date()) + "  " + msg)
        while (driveLog.size > 3) driveLog.removeLast()
        if (::driveLogText.isInitialized) driveLogText.text = driveLog.joinToString("\n")
    }

    private fun updateDriveButtons() {
        val on = scenario.running
        driveBtn.text = if (on) "■ 주행 시뮬레이션 중지" else "▶ 주행 시뮬레이션 시작"
        driveBtn.setTextColor(if (on) TEXT else BG)
        driveBtn.background = rounded(if (on) RED else GREEN, 999)
        audioChip.text = if (simPlayAudio) "엔진 소리 들려주기: 켜짐" else "엔진 소리 들려주기: 꺼짐"
        audioChip.setTextColor(if (simPlayAudio) TEXT else MUTED)
        knockBtn.alpha = if (on) 1f else 0.4f
    }

    // =====================================================================
    // 후진 주차 시뮬레이터 (급발진 의심 상황 재현)

    // =====================================================================

    private var lastBeepAt = 0L
    private var lastAlarmAt = 0L
    private var overlayPulse = false
    private var simMessage = ""

    private fun stepSim() {
        val now = System.currentTimeMillis()
        val dt = (now - lastSimTick).coerceIn(1, 200)
        lastSimTick = now
        val before = sim.phase
        sim.step(now, dt)

        // 후방 감지 경고음: 가까울수록 빠르게
        if (sim.gear == ReverseSimModel.Gear.R && sim.phase != ReverseSimModel.Phase.CRASHED) {
            val interval = when { sim.gapM < 0.6f -> 120L; sim.gapM < 1f -> 250L; sim.gapM < 2f -> 500L; sim.gapM < 3f -> 900L; else -> 0L }
            if (interval > 0 && now - lastBeepAt >= interval) { beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 70); lastBeepAt = now }
        }
        if (sim.phase == ReverseSimModel.Phase.SUA && now - lastAlarmAt > 1_400) {
            beeper?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500); lastAlarmAt = now
        }
        if (before == ReverseSimModel.Phase.SUA && sim.phase != ReverseSimModel.Phase.SUA) {
            speaker.say(if (sim.phase == ReverseSimModel.Phase.CRASHED) "충돌했습니다." else "차량이 멈췄습니다.")
            showSuaResult()
        }
        if (before != ReverseSimModel.Phase.CRASHED && sim.phase == ReverseSimModel.Phase.CRASHED && before != ReverseSimModel.Phase.SUA) {
            simMessage = "후방 차량과 닿았습니다. '처음 위치로'를 눌러 다시 시작하세요."
        }
        renderSim(now)
    }

    private fun onAccelTap() {
        val now = System.currentTimeMillis()
        if (sim.gear != ReverseSimModel.Gear.R) { simMessage = "먼저 기어를 R(후진)로 바꾸세요."; return }
        if (sim.tapAccel(now)) startSua()
    }

    private fun startSua() {
        muteUntil = System.currentTimeMillis() + 15_000
        speaker.alarm("급발진 의심 상황입니다. 브레이크를 밟으세요.")
        suaLive.visibility = View.VISIBLE; suaResult.visibility = View.GONE
        suaOverlay.visibility = View.VISIBLE
        simScene.flash = true
    }

    private fun showSuaResult() {
        val crashed = sim.phase == ReverseSimModel.Phase.CRASHED
        suaLive.visibility = View.GONE; suaResult.visibility = View.VISIBLE
        suaOverlay.setBackgroundColor(0xF2240A0D.toInt())
        suaResultTitle.text = if (crashed) "후방 차량과 충돌했습니다 (시뮬레이션)" else "차량이 멈췄습니다"
        suaResultTitle.setTextColor(if (crashed) RED else GREEN)
        val react = sim.reactionMs?.let { "%.1f초".format(it / 1000f) } ?: "브레이크 없음"
        suaResultBody.text = "경고 순간 후방 거리 %.1f m   ·   가장 가까웠던 거리 %.1f m\n최고 속도 %.0f km/h   ·   브레이크 반응 시간 %s   ·   가속 페달 연타 %d회"
            .format(sim.gapAtSua, sim.minGap, sim.peakSpeed, react, sim.tapsAtSua)
        selectNote(null)
    }

    private var chosenNote: String? = null
    private fun selectNote(note: String?) {
        chosenNote = note
        for (chip in noteChips) {
            val on = chip.text == note
            chip.setTextColor(if (on) BG else TEXT)
            chip.background = rounded(if (on) AMBER else SURFACE2, 999)
        }
    }

    private fun closeSua(save: Boolean) {
        if (save) {
            suaStore.add(SuaEvent.of(sim, chosenNote ?: "선택 안 함"))
            refreshSua()
            simMessage = "급발진 경험을 저장했습니다 → '기록' 탭에서 다시 볼 수 있어요."
        } else simMessage = ""
        suaOverlay.visibility = View.GONE
        simScene.flash = false
        sim.reset()
    }

    private fun renderSim(now: Long) {
        simScene.invalidate()
        simSpeed.text = "%.1f".format(sim.speedKmh)
        val zc = when (sim.zone) { ReverseSimModel.Zone.SAFE -> GREEN; ReverseSimModel.Zone.CAUTION -> AMBER; else -> RED }
        simGap.text = if (sim.gear == ReverseSimModel.Gear.R) "후방 차량까지 %.1f m · %s".format(sim.gapM, sim.zone.label) else "후방 차량까지 %.1f m".format(sim.gapM)
        simGap.setTextColor(if (sim.gear == ReverseSimModel.Gear.R) zc else MUTED)
        for ((g, v) in gearButtons) {
            val on = sim.gear == g
            v.setTextColor(if (on) BG else TEXT)
            v.background = rounded(if (on) (if (g == ReverseSimModel.Gear.R) AMBER else BLUE) else SURFACE2, 14)
        }
        val taps = sim.recentTaps
        simTapText.text = "가속 페달 연타  $taps / ${sim.rapidTaps}  (${sim.rapidWindowMs / 1000.0}초 안)"
        simTapText.setTextColor(if (taps >= sim.rapidTaps - 1) RED else if (taps >= 2) AMBER else MUTED)
        (simTapFill.layoutParams as LinearLayout.LayoutParams).weight = (taps.toFloat() / sim.rapidTaps).coerceAtMost(1f)
        simTapFill.background = rounded(if (taps >= sim.rapidTaps - 1) RED else AMBER, 999)
        simTapFill.requestLayout()
        val (phaseText, phaseColor) = when (sim.phase) {
            ReverseSimModel.Phase.READY -> (if (sim.gear == ReverseSimModel.Gear.R) "후진 중" else "대기") to (if (sim.gear == ReverseSimModel.Gear.R) BLUE else MUTED)
            ReverseSimModel.Phase.SUA -> "급발진 의심" to RED
            ReverseSimModel.Phase.STOPPED -> "정지" to GREEN
            ReverseSimModel.Phase.CRASHED -> "충돌" to RED
        }
        simPhase.text = phaseText; simPhase.setTextColor(phaseColor); simPhase.background = rounded(phaseColor.tint(), 999)
        simMsg.text = simMessage
        accelPedal.alpha = if (sim.gear == ReverseSimModel.Gear.R) 1f else 0.45f

        if (suaOverlay.visibility == View.VISIBLE && sim.phase == ReverseSimModel.Phase.SUA) {
            if ((now / 350) % 2 == 0L != overlayPulse) {
                overlayPulse = !overlayPulse
                suaOverlay.setBackgroundColor(if (overlayPulse) 0xF2C8141E.toInt() else 0xF27A0A10.toInt())
            }
            suaLiveText.text = "속도 %.0f km/h   ·   후방 차량까지 %.1f m".format(sim.speedKmh, sim.gapM)
        }
    }

    // =====================================================================
    // 화면
    // =====================================================================

    /** 상태 카드 단계. 색과 아이콘이 함께 바뀐다. */
    private enum class Level(val bg: Int, val accent: Int, val icon: String) {
        OK(SURFACE, GREEN, "✓"),
        INFO(SURFACE, BLUE, "i"),
        CAUTION(0xFF3A2E12.toInt(), AMBER, "!"),
        DANGER(0xFF4A1518.toInt(), RED, "!"),
    }

    private enum class Tab(val title: String) { SOUND("소리 모니터링"), REVERSE("후진 주차 · 급발진"), TELLTALE("경고등 안내"), RECORDS("기록") }

    private val tabButtons = HashMap<Tab, TextView>()
    private val pages = HashMap<Tab, View>()
    private var currentTab = Tab.SOUND

    // 공통
    private lateinit var statusPill: TextView
    private lateinit var ttsPill: TextView
    private lateinit var suaOverlay: FrameLayout
    // 소리 탭
    private lateinit var driveScene: DriveSceneView
    private lateinit var driveBtn: TextView
    private lateinit var driveStatus: TextView
    private lateinit var driveLogText: TextView
    private lateinit var audioChip: TextView
    private lateinit var knockBtn: TextView
    private lateinit var gearVal: TextView
    private lateinit var speedVal: TextView
    private lateinit var brakeVal: TextView
    private lateinit var condVal: TextView
    private lateinit var odoVal: TextView
    private lateinit var heroCard: LinearLayout
    private lateinit var heroIcon: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var micDb: TextView
    private lateinit var micState: TextView
    private lateinit var micLegend: TextView
    private lateinit var graph: MonitorGraph
    private lateinit var chipWindow: TextView
    private lateinit var chipWiper: TextView
    private lateinit var chipFan: TextView
    private val steps = ArrayList<Pair<TextView, TextView>>()     // 분석 과정 단계 (번호 원, 값)
    private lateinit var soundCardList: LinearLayout
    // 후진 탭
    private lateinit var simScene: ParkingSceneView
    private lateinit var simSpeed: TextView
    private lateinit var simGap: TextView
    private lateinit var simPhase: TextView
    private lateinit var simTapText: TextView
    private lateinit var simTapFill: View
    private lateinit var simMsg: TextView
    private lateinit var accelPedal: PedalView
    private val gearButtons = LinkedHashMap<ReverseSimModel.Gear, TextView>()
    private lateinit var suaLive: LinearLayout
    private lateinit var suaLiveText: TextView
    private lateinit var suaResult: LinearLayout
    private lateinit var suaResultTitle: TextView
    private lateinit var suaResultBody: TextView
    private val noteChips = ArrayList<TextView>()
    // 기록 탭
    private lateinit var cardCount: TextView
    private lateinit var cardList: LinearLayout
    private lateinit var suaCount: TextView
    private lateinit var suaList: LinearLayout

    private val resetAlert = Runnable {
        showAlert(Level.OK, "정상 감시 중", "앱을 켜 두면 자동으로 기록합니다. 주행 중에는 화면을 보지 않아도 됩니다.")
    }

    private fun buildUi() {
        // ---- 머리줄: 앱 이름 + 탭 + 상태 ----
        ttsPill = pill("음성 초기화 중", MUTED, size = 14f)
        statusPill = pill("● 감시 중", GREEN, size = 14f)
        val tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (t in Tab.values()) {
            val b = pill(t.title, TEXT, SURFACE, 17f).apply { setPadding(dp(22), dp(10), dp(22), dp(10)); setOnClickListener { selectTab(t) } }
            tabButtons[t] = b
            tabsRow.addView(b, lp(WRAP, WRAP).apply { marginEnd = dp(10) })
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("movens", 28f, TEXT, true), lp(WRAP, WRAP).apply { marginEnd = dp(28) })
            addView(tabsRow, lp(0, WRAP, 1f))
            addView(ttsPill); addView(hspace(10)); addView(statusPill)
        }

        val pageHost = FrameLayout(this)
        pages[Tab.SOUND] = buildSoundPage()
        pages[Tab.REVERSE] = buildReversePage()
        pages[Tab.TELLTALE] = buildTelltalePage()
        pages[Tab.RECORDS] = buildRecordsPage()
        for (pg in pages.values) pageHost.addView(pg, FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            setPadding(dp(32), dp(22), dp(32), dp(16))
            addView(header)
            addView(pageHost, lp(MATCH, 0, 1f).apply { topMargin = dp(18) })
        }

        // 경고등 배너는 모든 탭 위, 오른쪽 위에 뜬다 (휴대폰 알림처럼)
        val bannerLayer = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(84); marginEnd = dp(28) }
        suaOverlay = buildSuaOverlay()

        val root = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(telltales.bannerStack, bannerLayer)
            addView(suaOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(root)
        selectTab(Tab.SOUND)
        updateDriveButtons()
        resetAlert.run()
    }

    private fun selectTab(t: Tab) {
        currentTab = t
        for ((k, v) in pages) v.visibility = if (k == t) View.VISIBLE else View.GONE
        for ((k, b) in tabButtons) {
            val on = k == t
            b.setTextColor(if (on) BG else TEXT_SUB)
            b.background = rounded(if (on) TEXT else SURFACE, 999)
        }
    }

    // ---------- ① 소리 모니터링 ----------

    private fun buildSoundPage(): View {
        // 주행 시뮬레이션 막대
        driveBtn = pill("▶ 주행 시뮬레이션 시작", BG, GREEN, 18f).apply {
            setPadding(dp(24), dp(14), dp(24), dp(14)); setOnClickListener { toggleDrive() }
        }
        driveStatus = label("", 15f, TEXT_SUB, true)
        driveLogText = label("", 13f, MUTED)
        audioChip = pill("", TEXT, SURFACE2, 14f).apply { setOnClickListener { toggleDriveAudio() } }
        knockBtn = pill("엔진 이상 소리 만들기", AMBER, AMBER.tint(), 14f).apply { setOnClickListener { forceKnock() } }
        val shortBtn = pill("짧은 소리 (재확인)", TEXT_SUB, SURFACE2, 14f).apply { setOnClickListener { forceKnock(2_000) } }
        val ttBtn = pill("경고등 여러 개 켜기", RED, RED.tint(), 14f).apply { setOnClickListener { randomTelltale() } }
        val driveBar = card(20, 14).apply {
            addView(hbox(
                driveBtn,
                vbox(driveStatus, driveLogText).apply { layoutParams = lp(0, WRAP, 1f).apply { marginStart = dp(18); marginEnd = dp(12) } },
                audioChip, hspace(8), knockBtn, hspace(8), shortBtn, hspace(8), ttBtn,
            ))
        }
        // 차량 신호 타일
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("기어", "속도", "브레이크", "주행 상황", "주행거리").forEachIndexed { i, title ->
            val value = label("-", 28f, TEXT, true)
            val box = card(20, 14).apply { background = rounded(SURFACE, 16); addView(label(title, 14f, MUTED)); addView(value) }
            tiles.addView(box, lp(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(12) })
            when (i) { 0 -> gearVal = value; 1 -> speedVal = value; 2 -> brakeVal = value; 3 -> condVal = value; else -> odoVal = value }
        }

        // 상태 카드
        heroIcon = label("✓", 28f, BG, true).apply { gravity = Gravity.CENTER }
        heroTitle = label("", 28f, TEXT, true)
        heroSub = label("", 18f, TEXT_SUB).apply { setPadding(0, dp(6), 0, 0) }
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            setOnClickListener { resetAlert.run() }
            addView(heroIcon, lp(dp(60), dp(60)))
            addView(vbox(heroTitle, heroSub).apply { setPadding(dp(24), 0, 0, 0) }, lp(0, WRAP, 1f))
        }

        // 엔진 소리 카드 (그래프)
        micState = pill("대기 중", MUTED, size = 14f)
        micDb = label("--", 44f, TEXT, true)
        graph = MonitorGraph(this)
        micLegend = label("", 15f, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) }
        chipWindow = pill("", MUTED, size = 14f); chipWiper = pill("", MUTED, size = 14f); chipFan = pill("", MUTED, size = 14f)
        val graphCard = card().apply {
            addView(cardHeader("엔진 소리", micState))
            addView(hbox(micDb, label(" dBFS", 18f, MUTED).apply { setPadding(0, 0, 0, dp(8)) }, gravity = Gravity.BOTTOM))
            addView(label("최근 60초 (막대: 1초 음량 · 흰 선: 평소 · 빨간 선: 경고선)", 13f, MUTED).apply { setPadding(0, dp(4), 0, dp(6)) })
            addView(graph, lp(MATCH, dp(130)))
            addView(micLegend)
            addView(hbox(chipWindow, hspace(8), chipWiper, hspace(8), chipFan))
        }

        // 분석 과정 카드 (백엔드가 하는 일을 단계별로)
        val pipeCard = card().apply {
            addView(cardHeader("분석 과정 (실시간)", pill("앱 안에서만 처리", MUTED, size = 13f)))
            addView(vspace(8))
            listOf("마이크 입력", "특징 추출", "평소 소리와 비교", "판정", "반복 확인 → 알림").forEachIndexed { i, title ->
                val num = label("${i + 1}", 16f, BG, true).apply { gravity = Gravity.CENTER }
                val value = label("", 15f, TEXT_SUB)
                steps += num to value
                addView(hbox(
                    num.apply { layoutParams = lp(dp(32), dp(32)).apply { marginEnd = dp(14) } },
                    vbox(label(title, 16f, TEXT, true), value).apply { layoutParams = lp(0, WRAP, 1f) },
                    gravity = Gravity.TOP,
                ).apply { setPadding(0, dp(7), 0, dp(7)) })
            }
        }

        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(graphCard, lp(0, WRAP, 1.15f))
            addView(pipeCard, lp(0, WRAP, 1f).apply { marginStart = dp(16) })
        }

        soundCardList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        driveScene = DriveSceneView(this).apply {
            background = rounded(SURFACE, 20); clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val page = vbox(
            driveBar.apply { layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(14) } },
            driveScene.apply { layoutParams = lp(MATCH, dp(330)).apply { bottomMargin = dp(14) } },
            tiles,
            heroCard.apply { layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(14) } },
            middle.apply { layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(14) } },
            hbox(label("최근 이상 소리 (10초 녹음)", 20f, TEXT, true).apply { layoutParams = lp(0, WRAP, 1f) },
                label("전체 기록은 '기록' 탭", 14f, MUTED)).apply { setPadding(0, dp(20), 0, dp(10)) },
            soundCardList,
        )
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(page) }
    }

    // ---------- ② 후진 주차 · 급발진 ----------

    private fun buildReversePage(): View {
        simScene = ParkingSceneView(this, sim)
        simPhase = pill("대기", MUTED, size = 15f)
        simSpeed = label("0.0", 56f, TEXT, true)
        simGap = label("", 20f, MUTED, true)
        val gearRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (g in ReverseSimModel.Gear.values()) {
            val b = label(g.name, 26f, TEXT, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    sim.setGear(g)
                    simMessage = if (g == ReverseSimModel.Gear.R) "후진 기어입니다. 가속 페달을 한 번씩 눌러 천천히 뒤로 가 보세요." else ""
                }
            }
            gearButtons[g] = b
            gearRow.addView(b, lp(0, WRAP, 1f).apply { if (g != ReverseSimModel.Gear.P) marginStart = dp(8) })
        }
        simTapText = label("", 15f, MUTED, true)
        simTapFill = View(this)
        val tapBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(SURFACE2, 999); weightSum = 1f
            addView(simTapFill, lp(0, MATCH, 0f))
        }
        val brakePedal = PedalView(this, accel = false).apply {
            onDown = { sim.setBrake(true, System.currentTimeMillis()) }
            onUp = { sim.setBrake(false, System.currentTimeMillis()) }
        }
        accelPedal = PedalView(this, accel = true).apply {
            onDown = { onAccelTap() }
        }
        val pedals = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(brakePedal, lp(0, dp(190), 1.3f))
            addView(accelPedal, lp(0, dp(230), 1f).apply { marginStart = dp(16) })
        }
        simMsg = label("", 16f, AMBER, true)
        val reset = pill("처음 위치로", TEXT, size = 15f).apply { setOnClickListener { sim.reset(); simMessage = ""; simScene.flash = false } }

        val control = card(26, 22).apply {
            addView(cardHeader("후진 주차 시뮬레이터", simPhase))
            addView(hbox(simSpeed, label(" km/h", 20f, MUTED).apply { setPadding(0, 0, 0, dp(12)) }, gravity = Gravity.BOTTOM))
            addView(simGap)
            addView(label("기어", 14f, MUTED).apply { setPadding(0, dp(14), 0, dp(6)) })
            addView(gearRow)
            addView(simTapText.apply { setPadding(0, dp(14), 0, dp(6)) })
            addView(tapBar, lp(MATCH, dp(8)))
            addView(pedals, lp(MATCH, WRAP).apply { topMargin = dp(16) })
            addView(label("① 기어 R  →  ② 가속 페달을 한 번씩 눌러 천천히 후진  →  ③ 가속 페달을 1.5초 안에 5번 이상 빠르게 누르면 급발진 상황이 재현됩니다. 브레이크는 누르고 있는 동안 밟힙니다.",
                14f, MUTED).apply { setPadding(0, dp(12), 0, dp(8)) })
            addView(hbox(simMsg.apply { layoutParams = lp(0, WRAP, 1f) }, reset))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(simScene, lp(0, MATCH, 1.05f))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(control) }, lp(0, MATCH, 1f).apply { marginStart = dp(16) })
        }
    }

    /** 급발진 의심 시 화면 전체를 덮는 경고 + 끝난 뒤 경험 저장 화면. */
    private fun buildSuaOverlay(): FrameLayout {
        suaLiveText = label("", 26f, TEXT, true).apply { gravity = Gravity.CENTER }
        val bigBrake = PedalView(this, accel = false).apply {
            onDown = { sim.setBrake(true, System.currentTimeMillis()) }
            onUp = { sim.setBrake(false, System.currentTimeMillis()) }
        }
        suaLive = vbox(
            GlyphView(this, Glyph.WARNING, TEXT, filled = false).apply { layoutParams = lp(dp(130), dp(130)) },
            label("급발진 의심 상황", 56f, TEXT, true).apply { gravity = Gravity.CENTER },
            label("브레이크를 끝까지 밟으세요!", 36f, 0xFFFFE08A.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16)) },
            suaLiveText,
            bigBrake.apply { layoutParams = lp(dp(300), dp(200)).apply { topMargin = dp(24) } },
            label("화면의 브레이크 페달을 누르고 있으세요", 18f, TEXT_SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) },
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }

        suaResultTitle = label("", 40f, GREEN, true).apply { gravity = Gravity.CENTER }
        suaResultBody = label("", 20f, TEXT_SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(26)); setLineSpacing(0f, 1.3f) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (n in listOf("가속 페달을 잘못 밟았어요", "차가 저절로 튀어 나갔어요", "잘 모르겠어요")) {
            val c = pill(n, TEXT, SURFACE2, 18f).apply { setPadding(dp(22), dp(14), dp(22), dp(14)); setOnClickListener { selectNote(n) } }
            noteChips += c
            chips.addView(c, lp(WRAP, WRAP).apply { marginStart = dp(6); marginEnd = dp(6) })
        }
        val save = pill("경험 저장", BG, BLUE, 20f).apply { setPadding(dp(36), dp(16), dp(36), dp(16)); setOnClickListener { closeSua(true) } }
        val skip = pill("저장하지 않음", TEXT_SUB, SURFACE2, 20f).apply { setPadding(dp(30), dp(16), dp(30), dp(16)); setOnClickListener { closeSua(false) } }
        suaResult = vbox(
            GlyphView(this, Glyph.BRAKE, AMBER).apply { layoutParams = lp(dp(96), dp(96)) },
            suaResultTitle,
            suaResultBody,
            label("이 순간을 어떻게 기억할까요? (정비소·보험 상담 때 참고 기록)", 20f, TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12)) },
            chips,
            hbox(save, hspace(16), skip, gravity = Gravity.CENTER).apply { setPadding(0, dp(28), 0, 0) },
            label("이 기록은 공식 사고기록장치(EDR)가 아니며, 원인이나 과실을 판정하지 않습니다.", 14f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) },
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }

        return FrameLayout(this).apply {
            visibility = View.GONE; isClickable = true; elevation = dpf(30f)
            setBackgroundColor(0xF2C8141E.toInt())
            addView(suaLive, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            addView(suaResult, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
    }

    // ---------- ③ 경고등 안내 ----------

    private fun buildTelltalePage(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        TelltaleCatalog.all.chunked(6).forEach { rowSpecs ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (s in rowSpecs) {
                val tile = vbox(
                    GlyphView(this, s.glyph, s.severity.color).apply { layoutParams = lp(dp(58), dp(58)) },
                    label(s.name, 13f, TEXT_SUB, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) },
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; background = rounded(SURFACE2, 16)
                    setPadding(dp(8), dp(12), dp(8), dp(12)); setOnClickListener { fireTelltale(s) }
                }
                row.addView(tile, lp(0, WRAP, 1f).apply { marginEnd = dp(10) })
            }
            repeat(6 - rowSpecs.size) { row.addView(View(this), lp(0, 1, 1f).apply { marginEnd = dp(10) }) }
            grid.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }
        val legend = hbox(
            pill("빨강 · 위험 → 알림 10분", RED, RED.tint(), 14f), hspace(8),
            pill("노랑 · 주의 → 알림 5분", AMBER, AMBER.tint(), 14f), hspace(8),
            pill("초록 · 안내 → 알림 2분", GREEN, GREEN.tint(), 14f),
        )
        val left = card().apply {
            addView(cardHeader("경고등 시뮬레이터"))
            addView(label("계기판에 경고등이 켜진 상황을 만들어 봅니다. 누르면 휴대폰 알림처럼 오른쪽 위에 뜨고, 쉬운 말로 소리 내어 알려 줍니다.", 15f, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
            addView(legend)
            addView(vspace(14))
            addView(grid)
            addView(label("실제 차량(Virtual Console)에서 경고등이 켜져도 같은 알림이 뜹니다 (경고 정보 접근 권한이 있을 때).", 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
        val right = card().apply {
            addView(cardHeader("지금 켜져 있는 경고등", pill("남은 시간이 지나면 자동으로 사라짐", MUTED, size = 12f)))
            addView(vspace(12))
            addView(telltales.activeList)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(left) }, lp(0, MATCH, 1.5f))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(right) }, lp(0, MATCH, 1f).apply { marginStart = dp(16) })
        }
    }

    // ---------- ④ 기록 ----------

    private fun buildRecordsPage(): View {
        cardCount = label("", 15f, MUTED)
        cardList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        suaCount = label("", 15f, MUTED)
        suaList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val left = card().apply {
            addView(cardHeader("정비 전달 카드", cardCount).apply { setOnLongClickListener { clearCards(); true } })
            addView(label("언제 · 주행거리 · 어떤 때 · 몇 번째인지 한 장으로 정리합니다. 머리줄을 길게 누르면 전체 삭제.", 14f, MUTED).apply { setPadding(0, dp(4), 0, dp(12)) })
            addView(cardList)
        }
        val right = card().apply {
            addView(cardHeader("급발진 경험", suaCount).apply { setOnLongClickListener { suaStore.clear(); refreshSua(); true } })
            addView(label("후진 중 급발진 의심 상황의 속도·거리·브레이크 흐름입니다. 머리줄을 길게 누르면 전체 삭제.", 14f, MUTED).apply { setPadding(0, dp(4), 0, dp(12)) })
            addView(suaList)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(left) }, lp(0, MATCH, 1.3f))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(right) }, lp(0, MATCH, 1f).apply { marginStart = dp(16) })
        }
    }

    // ---------- 갱신 ----------

    private fun render() {
        val g = gear?.name?.removePrefix("GEAR_")
        gearVal.text = when (g) { "PARK" -> "P"; "REVERSE" -> "R"; "NEUTRAL" -> "N"; "DRIVE" -> "D"; null -> "-"; else -> g }
        gearVal.setTextColor(if (g == "REVERSE" || g == "DRIVE") BLUE else TEXT)
        speedVal.text = "${speedKmh.toInt()} km/h"
        brakeVal.text = if (braking) "밟음" else "뗌"; brakeVal.setTextColor(if (braking) AMBER else TEXT)
        condVal.text = condition(); condVal.setTextColor(if (speedKmh < 1f) TEXT else BLUE)
        odoVal.text = odometerKm?.let { "%,d km".format(it) } ?: "-"

        val ttsOk = speaker.status == "준비됨"
        ttsPill.text = "음성 ${speaker.status}"; ttsPill.setTextColor(if (ttsOk) GREEN else MUTED)
        if (disabledSignals.isEmpty()) { statusPill.text = "● 감시 중"; statusPill.setTextColor(GREEN) }
        else { statusPill.text = "● 꺼진 신호: ${disabledSignals.joinToString()}"; statusPill.setTextColor(AMBER) }

        // 엔진 소리: 현재 음량, 평소 기준선, 경고선(AnomalyDetector와 같은 기준)
        val f = lastFrame
        val db = f?.dbfs
        val now = System.currentTimeMillis()
        val learning = detector.learning
        val base = if (detector.level.n > 0) detector.level.avg else null
        val limit = if (!learning && base != null) detector.threshold else null
        micDb.text = db?.let { "%.0f".format(it) } ?: "--"
        micDb.setTextColor(if (db != null && limit != null && db > limit) AMBER else TEXT)
        val err = if (synth != null) null else micError
        val simOn = synth != null
        val (stateText, stateColor) = when {
            simOn && learning -> "주행 시뮬레이션 · 평소 엔진 소리 학습 ${detector.level.n}/${detector.learnTarget}초" to BLUE
            simOn && sound.suppressed -> "주행 시뮬레이션 · 창문·와이퍼·송풍으로 판단 중지" to AMBER
            simOn -> "주행 시뮬레이션 · 감시 중" to GREEN
            err != null -> err to RED
            db == null -> "대기 중" to MUTED
            player != null -> "녹음 재생 중 · 판단 중지" to AMBER
            now < muteUntil -> "안내음 중 · 판단 중지" to AMBER
            sound.suppressed -> "창문·와이퍼·송풍 · 판단 중지" to AMBER
            learning -> "평소 소리 학습 중 ${detector.level.n}/${detector.learnTarget}초" to BLUE
            else -> "감시 중" to GREEN
        }
        micState.text = stateText; micState.setTextColor(stateColor); micState.background = rounded(stateColor.tint(), 999)
        micLegend.text = when {
            base == null -> "평소 소리를 기준선으로 배웁니다"
            limit == null -> "평소 %.0f dBFS (학습 중)".format(base)
            else -> "평소 %.0f dBFS  ·  경고선 %.0f dBFS".format(base, limit)
        }
        graph.update(history.toList(), base, limit)
        setChip(chipWindow, if (windowOpen) "창문 열림" else "창문 닫힘", windowOpen)
        setChip(chipWiper, if (wiperOn) "와이퍼 작동" else "와이퍼 정지", wiperOn)
        setChip(chipFan, if (fanHigh) "송풍 강함" else "송풍 약함", fanHigh)

        // 분석 과정 5단계
        step(0, when {
            err != null -> "마이크를 쓸 수 없음: $err" to RED
            db == null -> "마이크 연결 대기 중" to MUTED
            sound.suppressed -> "듣는 중 · 지금은 판단을 멈춤 ($stateText)" to AMBER
            simOn -> "시연용 합성 엔진 소리를 마이크 대신 1초 단위로 분석 중" + (if (simOut != null) " · 스피커로 재생 중" else "") to GREEN
            else -> "차량 마이크 16 kHz · 1초 단위로 듣는 중 · 최근 10초는 메모리에만 보관" to GREEN
        })
        // 주행 장면
        driveScene.running = scenario.running
        driveScene.targetSpeed = speedKmh
        driveScene.braking = braking
        driveScene.raining = scenario.raining(now) || wiperOn
        driveScene.knocking = scenario.knocking(now)
        driveScene.gearText = if (scenario.running) (if (vcGear == "Drive" || !driveVC) "D" else "P") else gearVal.text.toString()
        driveScene.telltaleIcons = telltales.active.map { it.spec.glyph to it.spec.severity.color }
        driveScene.alertColor = when {
            pending != null -> AMBER
            now - lastConfirmedAt < 6_000 -> RED
            telltales.active.any { it.spec.severity == Severity.RED_LV && now - it.start < 8_000 } -> RED
            else -> null
        }
        driveScene.caption = when {
            !scenario.running -> ""
            detector.learning -> "평소 엔진 소리 학습 중 ${detector.level.n}/${detector.learnTarget}초"
            pending != null -> "평소와 다른 소리 감지 → 다시 확인하는 중"
            now - lastConfirmedAt < 6_000 -> "재확인됨 → 녹음과 정비 카드를 저장했습니다"
            scenario.raining(now) -> "비 · 와이퍼 작동 → 소리 판단을 잠시 멈춤"
            scenario.knocking(now) -> "엔진에서 '딱딱' 소리…"
            braking -> "감속 중 (브레이크)"
            else -> "엔진 소리 감시 중 · ${condition()}"
        }
        // 주행 시뮬레이션 상태줄
        driveStatus.text = if (scenario.running) {
            val phase = when {
                detector.learning -> "평소 엔진 소리 학습 중 ${detector.level.n}/${detector.learnTarget}초"
                scenario.knocking(now) -> "이상 소리 나는 중"
                else -> "다음 무작위 사건 약 ${((scenario.nextEventAt - now) / 1000).coerceAtLeast(0)}초 뒤"
            }
            "주행 중 ${speedKmh.toInt()} km/h · $phase" + when {
                driveVC -> " · 왼쪽 3D 차량 연동 중 (기어 ${if (vcGear == "Drive") "D" else "P"})"
                realSpeed > 1f -> " · 속도: Virtual Console"
                else -> " · 앱 안에서만 주행 (Virtual Console 연결 안 됨)"
            } + if (scenario.raining(now)) " · 비 (와이퍼)" else ""
        } else "시작하면 엔진 소리를 내며 달리고(왼쪽 3D 차량도 함께), 이상 소리·비·경고등이 무작위로 생겨 알림으로 뜹니다." +
            when (vc.connected) { true -> "  · Virtual Console 연결됨"; false -> "  · Virtual Console 연결 안 됨"; null -> "" }
        step(1, if (f == null) "아직 소리 없음" to MUTED
            else "음량 %.0f dBFS  ·  음색(영교차율) %.3f  ·  충격도 %.1f".format(f.dbfs, f.zcr, f.crest) to BLUE)
        step(2, when {
            base == null -> "평소 소리를 모으는 중" to MUTED
            learning -> "학습 중 ${detector.level.n}/${detector.learnTarget}초 · 지금까지 평소 %.0f dBFS".format(base) to BLUE
            db == null -> "평소 %.0f ± %.1f dB".format(base, detector.level.std) to MUTED
            else -> "평소 %.0f ± %.1f dB → 지금 %+.0f dB (경고선 %+.0f dB) · 음색 차이 %.1fσ"
                .format(base, detector.level.std, db - base, detector.threshold - base, detector.lastToneZ) to
                (if (detector.lastLoud || detector.lastOdd) AMBER else GREEN)
        })
        step(3, when {
            learning || db == null -> "학습이 끝나면 판정합니다" to MUTED
            pending != null -> "의심 → 재확인 중 (${((pendingUntil - now) / 1000).coerceAtLeast(0) + 1}초 남음 · 다시 들림 $pendingHits/$CONFIRM_HITS)" to AMBER
            now - lastConfirmedAt < 4_000 -> "재확인됨 → 10초 녹음과 정비 카드를 저장했습니다" to RED
            now - lastAnomalyAt < 4_000 -> "재확인 결과 일시적인 소리 → 기록 안 함" to MUTED
            sound.suppressed -> "판단 중지" to AMBER
            detector.streak > 0 -> "평소와 다른 소리 연속 ${detector.streak}/2초" to AMBER
            detector.impulseCount > 0 -> "충격음 ${detector.impulseCount}/3회 (10초 안)" to AMBER
            else -> "정상" to GREEN
        })
        val recent = store.all.count { it.kind == "이상 소리" && it.time >= now - REPEAT_WINDOW_MS }
        step(4, when {
            recent >= 3 -> "최근 10분 이상 소리 $recent/3회 → 경고음 + 음성으로 정비 점검 안내" to RED
            recent > 0 -> "최근 10분 이상 소리 $recent/3회 · 3회가 되면 정비 점검을 안내합니다" to AMBER
            else -> "최근 10분 이상 소리 0/3회" to MUTED
        })
    }

    private fun step(i: Int, v: Pair<String, Int>) {
        val (num, value) = steps[i]
        value.text = v.first
        value.setTextColor(if (v.second == MUTED) MUTED else TEXT_SUB)
        num.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(v.second) }
    }

    /** 오탐 억제 조건 칩. 켜져 있으면(=소리 판단 중지 사유) 주황색. */
    private fun setChip(chip: TextView, text: String, active: Boolean) {
        chip.text = text
        chip.setTextColor(if (active) AMBER else MUTED)
        chip.background = rounded(if (active) AMBER.tint() else SURFACE2, 999)
    }

    private fun showAlert(level: Level, title: String, sub: String, sticky: Boolean = false) {
        heroCard.background = rounded(level.bg, 20, if (level == Level.OK) SURFACE2 else level.accent)
        heroIcon.text = level.icon
        heroIcon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(level.accent) }
        heroTitle.text = title
        heroSub.text = sub
        ui.removeCallbacks(resetAlert)
        if (level != Level.OK && !sticky) ui.postDelayed(resetAlert, 10_000)
        if (level == Level.DANGER && currentTab != Tab.SOUND && suaOverlay.visibility != View.VISIBLE) selectTab(Tab.SOUND)
    }

    private fun refreshCards() {
        soundCardList.removeAllViews()
        val sounds = store.all.filter { it.kind == "이상 소리" }.take(5)
        if (sounds.isEmpty()) soundCardList.addView(label("아직 이상 소리 기록이 없습니다.", 16f, MUTED))
        for (c in sounds) soundCardList.addView(cardRow(c), lp(MATCH, WRAP).apply { bottomMargin = dp(10) })

        cardList.removeAllViews()
        val clips = store.all.count { it.clipPath != null }
        cardCount.text = if (store.all.isEmpty()) "" else "${store.all.size}건 · 녹음 ${clips}개"
        if (store.all.isEmpty()) {
            cardList.addView(label("아직 기록이 없습니다. 이상 소리 · 페달 경고 · 경고등이 생기면 여기에 쌓입니다.", 16f, MUTED))
            return
        }
        for (c in store.all.take(30)) cardList.addView(cardRow(c), lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
    }

    private fun cardRow(c: Card): View {
        val color = when (c.kind) { "페달 경고" -> RED; "경고등" -> AMBER; else -> BLUE }
        val badge = pill(c.kind, color, color.tint(), 14f)
        val time = SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(Date(c.time))
        val meta = listOfNotNull(time, c.odometerKm?.let { "%,d km".format(it) }, c.condition).joinToString("  ·  ")
        val texts = vbox(label(c.detail, 18f, TEXT, true), label(meta, 14f, MUTED)).apply { setPadding(dp(16), 0, dp(16), 0) }
        val count = label("${c.count}회째", 18f, if (c.count >= 3) color else TEXT_SUB, true)
        val clip = c.clipPath?.takeIf { File(it).exists() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (clip != null && clip == playingPath) 0xFF2B3442.toInt() else SURFACE2, 16)
            setPadding(dp(16), dp(12), dp(20), dp(12))
            addView(badge); addView(texts, lp(0, WRAP, 1f))
            if (clip != null) {
                addView(ClipWave(context, peaks(clip), color), lp(dp(170), dp(40)).apply { marginEnd = dp(14) })
                val playing = clip == playingPath
                addView(pill(if (playing) "■ 정지" else "▶ 다시 듣기", if (playing) AMBER else TEXT, size = 15f).apply {
                    setOnClickListener { togglePlay(clip) }
                }, lp(WRAP, WRAP).apply { marginEnd = dp(14) })
            }
            addView(count)
        }
    }

    private fun refreshSua() {
        suaList.removeAllViews()
        suaCount.text = if (suaStore.all.isEmpty()) "" else "${suaStore.all.size}건"
        if (suaStore.all.isEmpty()) {
            suaList.addView(label("아직 저장된 경험이 없습니다. '후진 주차 · 급발진' 탭에서 재현해 볼 수 있어요.", 16f, MUTED))
            return
        }
        for (e in suaStore.all.take(20)) {
            val color = if (e.outcome == "충돌") RED else GREEN
            val time = SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(Date(e.time))
            val react = e.reactionMs?.let { "반응 %.1f초".format(it / 1000f) } ?: "브레이크 없음"
            val box = vbox(
                hbox(pill(e.outcome, color, color.tint(), 14f),
                    label("  $time  ·  $react", 16f, TEXT, true).apply { layoutParams = lp(0, WRAP, 1f) }),
                label("기억: ${e.note}", 15f, AMBER).apply { setPadding(0, dp(6), 0, dp(2)) },
                label("경고 순간 거리 %.1f m → 가장 가까이 %.1f m  ·  최고 %.0f km/h  ·  연타 %d회".format(e.gapAtSua, e.minGap, e.peakSpeed, e.taps), 14f, MUTED),
                SuaTimelineView(this, e).apply { layoutParams = lp(MATCH, dp(110)).apply { topMargin = dp(10) } },
            ).apply { background = rounded(SURFACE2, 16); setPadding(dp(16), dp(14), dp(16), dp(14)) }
            suaList.addView(box, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        }
    }

    private fun clearCards() { stopPlayback(); store.clear(); wavePeaks.clear(); refreshCards() }

    // ---------- 녹음 다시 듣기 ----------

    private fun togglePlay(path: String) {
        val same = playingPath == path
        stopPlayback()
        if (same) return
        try {
            player = MediaPlayer().apply {
                setDataSource(path); prepare()
                setOnCompletionListener { stopPlayback() }
                start()
            }
            playingPath = path
            updateSuppression()
        } catch (e: Exception) { fail("재생", e); player = null; playingPath = null }
        refreshCards()
    }

    private fun stopPlayback() {
        val p = player ?: return
        runCatching { p.stop() }; p.release()
        player = null; playingPath = null
        muteUntil = max(muteUntil, System.currentTimeMillis() + 1_500)   // 스피커 잔향이 마이크로 들어가지 않게
        refreshCards()
    }

    /** 저장된 10초 WAV(16kHz mono 16bit)를 막대 120개로 줄인 파형. 한 번 계산하면 캐시한다. */
    private fun peaks(path: String): FloatArray = wavePeaks.getOrPut(path) {
        val bytes = runCatching { File(path).readBytes() }.getOrNull() ?: return@getOrPut FloatArray(0)
        val n = (bytes.size - 44) / 2
        if (n <= 0) return@getOrPut FloatArray(0)
        val bars = 120; val per = max(1, n / bars)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(bars) { i ->
            var peak = 0
            for (j in 0 until per) {
                val idx = i * per + j; if (idx >= n) break
                peak = max(peak, abs(bb.getShort(44 + idx * 2).toInt()))
            }
            peak / 32768f
        }
    }

    private fun saveWav(pcm: ShortArray): String {
        val f = File(filesDir, "clip_${System.currentTimeMillis()}.wav")
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size * 2)
        for (s in pcm) b.putShort(s)
        f.writeBytes(b.array())
        return f.path
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release(); player = null
        sound.synth = null; sound.out = null; simOut?.release(); simOut = null
        if (driveVC) vc.park()
        vc.release()
        sound.stop()
        speaker.release()
        telltales.release()
        beeper?.release()
        vehicle.release()
        car?.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val HISTORY_SEC = 60
        private const val REPEAT_WINDOW_MS = 10 * 60_000L   // 이상 소리 "반복"으로 세는 기간
        private const val CONFIRM_MS = 5_000L                // 재확인: 의심 후 이만큼 더 들어 본다
        private const val CONFIRM_HITS = 2                   // 그 사이 이만큼 다시 들리면 확정
    }
}
