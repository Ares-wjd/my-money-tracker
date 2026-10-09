package com.mymoneytracker.core

/** GitHub 릴리스(dev-latest) 정보에서 빌드 번호를 읽는다. */
object UpdateInfo {
    private val bodyPattern = Regex("""versionCode:\s*(\d+)""")
    private val namePattern = Regex("""빌드\s*#(\d+)""")

    fun parseVersionCode(name: String?, body: String?): Int? =
        body?.let { bodyPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: name?.let { namePattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** 새 버전 안내를 보여줄지. 사용자가 "나중에" 를 누른 빌드는 다시 묻지 않는다. */
    fun shouldNotify(latest: Int?, installed: Int, skipped: Int?): Boolean =
        latest != null && latest > installed && latest != skipped
}
