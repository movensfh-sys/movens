package ai.movens.app

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pleos Connect Emulator의 Virtual Console(에뮬레이터 안 127.0.0.1:8088) REST API로 차량 상태를 바꾼다.
 * 시연용: 주행 시뮬레이션을 시작하면 왼쪽 클러스터의 3D 차량이 실제로 기어 D로 달리고, 경고등·와이퍼가 켜진다.
 * 실차에는 이 서버가 없으므로 연결이 안 되면 조용히 앱 안 시뮬레이션만 한다.
 *
 * 확인한 엔드포인트 (모두 PUT, 쿼리 파라미터):
 *  /vehicle/gear?gear=Park|Reverse|Neutral|Drive
 *  /vehicle/controls?speed=&accelerator=&brake=&steering=
 *  /vehicle/telltales/simulator?type=LowVoltBattery&state=On|Off|Blink&color=Red|Yellow|Green
 *  /vehicle/body?front_wiper=Off|Low|High
 */
class VirtualConsole(private val base: String = "http://127.0.0.1:8088") {
    private val exec = Executors.newSingleThreadExecutor()
    private val controlsBusy = AtomicBoolean(false)
    @Volatile var connected: Boolean? = null; private set    // null = 아직 모름
    @Volatile var lastError: String? = null; private set

    fun probe() = exec.execute { call("GET", "/vehicle/state", emptyMap()) }

    fun gear(g: String) = put("/vehicle/gear", mapOf("gear" to g))

    /** 속도·페달은 자주 보내므로, 앞 요청이 끝나지 않았으면 이번 것은 건너뛴다. */
    fun controls(speedKmh: Float, acceleratorPct: Int, brakePct: Int) {
        if (connected == false || !controlsBusy.compareAndSet(false, true)) return
        exec.execute {
            try {
                call("PUT", "/vehicle/controls", mapOf("speed" to "%.1f".format(java.util.Locale.US, speedKmh),
                    "accelerator" to "$acceleratorPct", "brake" to "$brakePct", "steering" to "0"))
            } finally { controlsBusy.set(false) }
        }
    }

    /** 출발 준비: 열려 있는 프렁크·트렁크를 닫고 기어를 넣는다 (프렁크가 열린 채 달리지 않게). */
    fun prepareDrive(gear: String) = exec.execute {
        call("PUT", "/vehicle/body", mapOf("frunk" to "0", "trunk" to "0"))
        call("PUT", "/vehicle/gear", mapOf("gear" to gear))
    }

    /** 정지: 속도 0 → 기어 P. 건너뛰지 않고 반드시 보낸다. */
    fun park() = exec.execute {
        call("PUT", "/vehicle/controls", mapOf("speed" to "0", "accelerator" to "0", "brake" to "0", "steering" to "0"))
        call("PUT", "/vehicle/gear", mapOf("gear" to "Park"))
    }

    fun telltale(type: String, on: Boolean, color: String) =
        put("/vehicle/telltales/simulator", mapOf("type" to type, "state" to if (on) "On" else "Off", "color" to color))

    fun wiper(mode: String) = put("/vehicle/body", mapOf("front_wiper" to mode))

    private fun put(path: String, params: Map<String, String>) = exec.execute { call("PUT", path, params) }

    private fun call(method: String, path: String, params: Map<String, String>) {
        try {
            val q = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            val c = URL(base + path + if (q.isEmpty()) "" else "?$q").openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 800; c.readTimeout = 2_000; c.useCaches = false
            val code = c.responseCode
            runCatching { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } }
            c.disconnect()
            connected = code in 200..299
            if (code !in 200..299) lastError = "$path → HTTP $code"
        } catch (e: Exception) {
            connected = false; lastError = e.message
            Log.w("movens", "Virtual Console $path 실패: ${e.message}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun release() = exec.shutdown()

    companion object {
        /** 앱 경고등 키(Pleos TelltaleType 이름) → Virtual Console 경고등 type. 콘솔에 없는 것은 null. */
        fun telltaleType(key: String): String? = when (key) {
            "LOW_VOLT_BATTERY" -> "LowVoltBattery"
            "ENGINE_OIL_PRESSURE" -> "EngineOilPressure"
            "ENGINE_TEMPERATURE" -> "EngineTemperature"
            "PARKING_BRAKE" -> "ParkingBrake"
            "ELECTRONIC_PARKING_BRAKE" -> "ElectronicParkingBrake"
            "SEAT_BELT" -> "SeatBelt"
            "AIRBAG" -> "Airbag"
            "DOOR_OPEN" -> "DoorOpen"
            "MASTER_WARNING" -> "MasterWarning"
            "EV_SYSTEM" -> "EVSystem"
            "POWER_DOWN" -> "PowerDown"
            "ENGINE" -> "Engine"
            "TPMS" -> "TPMS"
            "ABS" -> "ABS"
            "ESC" -> "ESC"
            "MDPS" -> "MDPS"
            "AUTO_HOLD" -> "AutoHold"
            "HEADLAMP_LOW" -> "HeadlampLow"
            else -> null
        }

        fun color(s: Severity) = when (s) { Severity.RED_LV -> "Red"; Severity.YELLOW_LV -> "Yellow"; Severity.GREEN_LV -> "Green" }
    }
}
