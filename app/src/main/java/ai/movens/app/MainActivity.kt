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
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * movens 메인 화면. 시동과 함께 자동으로 동작하며 설정 화면은 없다.
 *  센서(Vehicle SDK, Car API, 마이크) → 상황 판단 → 기능 엔진(PedalRule, AnomalyDetector, 경고등 안내) → 출력(음성, 큰 글씨, 정비 카드)
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
    private var micText = "대기 중"

    // ---- 부품 ----
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private lateinit var speaker: Speaker
    private lateinit var store: CardStore
    private val pedal = PedalRule()
    private val detector = AnomalyDetector()
    private lateinit var sound: SoundMonitor
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { evaluatePedal(); render(); ui.postDelayed(this, 500) } }

    // ---- 화면 ----
    private lateinit var signals: TextView
    private lateinit var alert: TextView
    private lateinit var cards: TextView
    private val resetAlert = Runnable { showAlert("정상 감시 중", Color.WHITE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        store = CardStore(this)
        speaker = Speaker(this)
        sound = SoundMonitor(detector, ::onSoundFrame, ::onSoundAnomaly)
        vehicle = Vehicle(this).also { it.initialize() }
        registerVehicleSignals()

        val missing = arrayOf(Car.PERMISSION_SPEED, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startPermissionedInputs() else requestPermissions(missing.toTypedArray(), 1)
        ui.post(ticker)
        refreshCards()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = startPermissionedInputs()

    // ---------- 입력 ----------

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
        if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            try {
                car = Car.createCar(this)
                val pm = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(v: CarPropertyValue<*>) { setSpeed((v.value as Float) * 3.6f) }
                    override fun onErrorEvent(propId: Int, zone: Int) { Log.w("movens", "speed error $propId") }
                }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            } catch (e: Exception) { fail("속도", e) }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) sound.start { micText = it }
        else micText = "권한 없음"
    }

    private fun setSpeed(kmh: Float) {
        speedKmh = kmh
        val now = System.currentTimeMillis()
        if (lastSpeedAt > 0 && now > lastSpeedAt) accelKmhPerS = (speedKmh - lastSpeed) / ((now - lastSpeedAt) / 1000f)
        lastSpeed = speedKmh; lastSpeedAt = now
        evaluatePedal()
    }

    private fun updateSuppression() { sound.suppressed = windowOpen || wiperOn || fanHigh }

    private fun fail(what: String, e: Exception) { Log.w("movens", "$what 실패: ${e.message}") }

    // ---------- 판단 ----------

    /** 지금 주행 상황을 한 단어로. 정비 카드의 "어떤 때 났는지"에 쓴다. */
    private fun condition(): String = when {
        speedKmh < 1f -> "정차 중"
        brake -> "브레이크 밟을 때"
        accelKmhPerS > 2f -> "가속 중"
        else -> "정속 주행"
    }

    private fun evaluatePedal() {
        val gearRD = gear == VehicleGear.GEAR_REVERSE || gear == VehicleGear.GEAR_DRIVE
        val a = pedal.update(System.currentTimeMillis(), gearRD, speedKmh, brake, cruise) ?: return
        speaker.alarm(a.speech)
        showAlert(a.speech, Color.rgb(220, 40, 40))
        val detail = "${gear?.name?.removePrefix("GEAR_")} ${speedKmh.toInt()}km/h, 브레이크 ${if (brake) "밟음" else "안 밟음"}"
        store.add("페달 경고", condition(), detail, odometerKm)
        refreshCards()
    }

    private fun onSoundFrame(f: SoundFrame) {
        micText = "%.0f dBFS ".format(f.dbfs) + when {
            sound.suppressed -> "(창문·와이퍼·송풍으로 일시 중지)"
            detector.learning -> "(기준선 학습 중 ${detector.level.n}/20)"
            else -> "(감시 중, 평소 %.0f dBFS)".format(detector.level.avg)
        }
    }

    /** 오디오 스레드에서 호출된다. 10초 클립을 저장하고 카드를 만든 뒤, 정차 중에 3회 이상이면 음성으로 안내한다. */
    private fun onSoundAnomaly(reason: String, clip: ShortArray) {
        val path = saveWav(clip)
        ui.post {
            val card = store.add("이상 소리", condition(), reason, odometerKm, path)
            refreshCards()
            if (card.count >= 3 && speedKmh < 1f) {
                speaker.say("평소와 다른 소리가 반복되었습니다. 정비 점검을 권장합니다.")
                showAlert("${card.condition} 평소와 다른 소리가 ${card.count}번 있었습니다.\n정비 점검을 권장합니다.", Color.rgb(255, 235, 150))
            } else showAlert("이상 소리 기록됨 (${card.count}회) — 주행 중에는 알리지 않습니다", Color.rgb(235, 235, 235))
        }
    }

    private fun onTelltales(t: Telltales) {
        for (d in t.telltales) {
            val type = d.type ?: continue
            val on = d.state == TelltaleState.ON || d.state == TelltaleState.BLINK
            if (!on) { litTelltales.remove(type); continue }
            if (!litTelltales.add(type)) continue   // 이미 안내한 경고
            val (name, level) = telltaleInfo(type.name) ?: continue
            val msg = "$name 경고입니다. ${LEVEL_TEXT[level]}"
            ui.post {
                speaker.say(msg)
                showAlert("⚠ $name\n${LEVEL_TEXT[level]}", if (level == 3) Color.rgb(255, 200, 200) else Color.rgb(255, 240, 180))
                store.add("경고등", condition(), "$name · ${LEVEL_TEXT[level]}", odometerKm)
                refreshCards()
            }
        }
    }

    /** 경고등 이름과 위험도. 1 계속 주행 가능 / 2 가까운 시일 내 점검 / 3 안전한 곳에 정차 후 확인. 표에 없으면 안내하지 않는다. */
    private fun telltaleInfo(type: String): Pair<String, Int>? = when (type) {
        "ENGINE_OIL_PRESSURE" -> "엔진 오일 압력" to 3
        "ENGINE_TEMPERATURE" -> "엔진 과열" to 3
        "LOW_VOLT_BATTERY" -> "배터리 충전 계통" to 3
        "AIRBAG" -> "에어백" to 3
        "POWER_DOWN" -> "출력 제한" to 3
        "MASTER_WARNING" -> "주요 경고" to 3
        "EV_SYSTEM" -> "전기차 시스템" to 3
        "ENGINE" -> "엔진 점검" to 2
        "ABS" -> "ABS" to 2
        "ESC" -> "차체 자세 제어" to 2
        "MDPS" -> "조향 장치" to 2
        "TPMS" -> "타이어 공기압" to 2
        "LOW_FUEL" -> "연료 부족" to 2
        "LOW_EV_BATTERY" -> "구동 배터리 부족" to 2
        "ELECTRONIC_PARKING_BRAKE", "PARKING_BRAKE" -> "주차 브레이크" to 1
        "SEAT_BELT" -> "안전벨트" to 1
        "DOOR_OPEN" -> "문 열림" to 1
        "TRUNK_OPEN" -> "트렁크 열림" to 1
        else -> null
    }

    // ---------- 출력 ----------

    private fun buildUi() {
        fun text(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }
        signals = text(20f, Color.DKGRAY)
        alert = text(40f, Color.BLACK).apply { gravity = Gravity.CENTER; setPadding(32, 40, 32, 40) }
        cards = text(20f, Color.BLACK).apply { setOnLongClickListener { store.clear(); refreshCards(); true } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(48, 24, 48, 24)
            addView(signals); addView(alert)
            addView(text(22f, Color.BLACK).apply { text = "정비 전달 카드 (길게 누르면 지움)"; setPadding(0, 24, 0, 8) })
            addView(ScrollView(this@MainActivity).apply { addView(cards) })
        }
        setContentView(root)
        showAlert("정상 감시 중", Color.WHITE)
    }

    private fun render() {
        val g = gear?.name?.removePrefix("GEAR_") ?: "?"
        signals.text = "기어 $g · 브레이크 ${if (brake) "밟음" else "뗌"} · 속도 ${speedKmh.toInt()} km/h · 크루즈 ${if (cruise) "ON" else "OFF"}" +
            " · 주행거리 ${odometerKm ?: "?"} km\n마이크 $micText · 음성 ${speaker.status}" +
            "\n창문 ${if (windowOpen) "열림" else "닫힘"} · 와이퍼 ${if (wiperOn) "작동" else "정지"} · 송풍 ${if (fanHigh) "강함" else "약함"}" +
            (if (disabledSignals.isEmpty()) "" else "\n사용 불가 신호: ${disabledSignals.joinToString()}")
    }

    private fun showAlert(text: String, bg: Int) {
        alert.text = text; alert.setBackgroundColor(bg)
        ui.removeCallbacks(resetAlert)
        if (bg != Color.WHITE) ui.postDelayed(resetAlert, 8_000)
    }

    private fun refreshCards() {
        cards.text = if (store.all.isEmpty()) "아직 기록이 없습니다." else store.all.take(30).joinToString("\n")
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
        sound.stop()
        speaker.release()
        vehicle.release()
        car?.disconnect()
        super.onDestroy()
    }

    companion object {
        private val LEVEL_TEXT = arrayOf("", "계속 주행할 수 있습니다", "가까운 시일 내 점검이 필요합니다", "안전한 곳에 정차 후 확인하세요")
    }
}
