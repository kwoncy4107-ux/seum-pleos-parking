package ai.seum.engine

/**
 * Real coordinates used in the demo and the pitch, picked from the bundled data (see data/README).
 * RealDataTest asserts each spot still produces the expected verdict, so the demo cannot silently drift.
 */
data class DemoSpot(
    val key: String,
    val title: String,
    val story: String,
    val pos: LatLon,
    /** Clock to judge at, so time-dependent advice is reproducible on stage. */
    val hour: Int,
    val minute: Int,
    val expect: Level,
    /** Direction the demo car drove in (degrees from north), along the street it stops on. */
    val headingDeg: Double = 0.0,
)

object DemoSpots {
    // Five of Seoul's most-enforced curb spots in 2025 (서울시 OA-22190, about 20 m cells), chosen where the
    // reason is a crosswalk corner, an intersection, a bus stop or a hydrant rather than a straight line.
    val all = listOf(
        DemoSpot(
            "hongdae", "홍대 앞 골목 · 마포구 잔다리로 24",
            "2025년 이 자리 단속 4,244건. 교차로 사방 횡단보도 · 교차로 모퉁이 · 소화전",
            LatLon(37.550911, 126.920492), 13, 0, Level.DANGER, headingDeg = 139.0,
        ),
        DemoSpot(
            "bukchon", "북촌 한옥마을 입구 · 종로구 북촌로 53",
            "1,451건. 골목 황색 점선 · 버스정류장 · 고정 단속 CCTV",
            LatLon(37.581691, 126.984999), 13, 0, Level.DANGER, headingDeg = 359.0,
        ),
        DemoSpot(
            "suyu_market", "수유 시장 골목 · 강북구 한천로139길 46",
            "2,362건. 사거리 횡단보도 4곳 · 소화전 · 고정 CCTV",
            LatLon(37.638641, 127.024742), 13, 0, Level.DANGER, headingDeg = 129.0,
        ),
        DemoSpot(
            "ikseon", "익선동 · 종로구 돈화문로 45",
            "2,761건. 교차로 모퉁이 · 횡단보도 · 고정 CCTV",
            LatLon(37.572498, 126.991473), 13, 0, Level.DANGER, headingDeg = 346.0,
        ),
        DemoSpot(
            "yeonnam", "연남동 · 마포구 동교로 95",
            "1,213건. 골목 꺾임 · 버스정류장 두 곳 · 소화전 · 고정 CCTV",
            LatLon(37.554329, 126.912253), 13, 0, Level.DANGER, headingDeg = 2.0,
        ),
    )

    fun byKey(k: String) = all.firstOrNull { it.key == k }
}
