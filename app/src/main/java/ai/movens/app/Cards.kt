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
    val all: MutableList<Card> = if (file.exists()) file.readLines().filter { it.isNotBlank() }.map { Card.fromJson(JSONObject(it)) }.toMutableList() else mutableListOf()

    fun add(kind: String, condition: String, detail: String, odometerKm: Int?, clipPath: String? = null): Card {
        val count = 1 + all.count { it.kind == kind && it.condition == condition }
        val card = Card(System.currentTimeMillis(), kind, condition, detail, odometerKm, count, clipPath)
        all.add(0, card)
        file.appendText(card.toJson().toString() + "\n")
        return card
    }

    fun clear() { all.clear(); file.delete() }
}
