package ai.movens.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 정비 전달 카드 한 장. "언제, 어떤 조건에서, 몇 번"을 한 줄로 정리한다. */
class Card(
    val time: Long,
    val kind: String,        // 이상 소리 / 페달 경고 / 경고등
    val condition: String,   // 정차 중 / 가속 중 / 정속 주행 / 브레이크 밟을 때
    val detail: String,
    val odometerKm: Int?,
    val count: Int,          // 같은 종류·조건의 누적 횟수
    val clipPath: String?,
) {
    fun toJson() = JSONObject().put("time", time).put("kind", kind).put("condition", condition).put("detail", detail)
        .put("odometerKm", odometerKm ?: JSONObject.NULL).put("count", count).put("clipPath", clipPath ?: JSONObject.NULL)

    override fun toString(): String {
        val when_ = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(time))
        val odo = odometerKm?.let { " · ${it}km" } ?: ""
        return "$when_$odo · $kind · $condition · $detail · ${count}회째"
    }

    companion object {
        fun fromJson(o: JSONObject) = Card(
            o.getLong("time"), o.getString("kind"), o.getString("condition"), o.getString("detail"),
            if (o.isNull("odometerKm")) null else o.getInt("odometerKm"), o.getInt("count"),
            if (o.isNull("clipPath")) null else o.getString("clipPath"),
        )
    }
}

/** 카드는 차량 안(앱 내부 저장소)에만 쌓인다. 한 줄에 카드 하나(JSON Lines). */
class CardStore(ctx: Context) {
    private val file = File(ctx.filesDir, "cards.jsonl")
    // 강제 종료로 쓰다 만 줄이 남을 수 있다. 깨진 줄만 건너뛰고 나머지 카드는 살린다.
    val all: MutableList<Card> = if (file.exists()) file.readLines().mapNotNull { runCatching { Card.fromJson(JSONObject(it)) }.getOrNull() }.toMutableList() else mutableListOf()

    /**
     * windowMs가 없으면 같은 종류·같은 조건의 누적 횟수를, 있으면 최근 windowMs 안의 같은 종류 횟수를 센다
     * (주행 중에는 조건이 계속 바뀌므로 이상 소리는 "최근 10분 안의 반복"으로 센다).
     */
    fun add(kind: String, condition: String, detail: String, odometerKm: Int?, clipPath: String? = null, windowMs: Long? = null): Card {
        val now = System.currentTimeMillis()
        val count = 1 + if (windowMs == null) all.count { it.kind == kind && it.condition == condition }
                        else all.count { it.kind == kind && it.time >= now - windowMs }
        val card = Card(now, kind, condition, detail, odometerKm, count, clipPath)
        all.add(0, card)
        file.appendText(card.toJson().toString() + "\n")
        return card
    }

    fun clear() { all.clear(); file.delete() }
}
