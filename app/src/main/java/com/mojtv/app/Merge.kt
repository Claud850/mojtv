package com.mojtv.app

object Merge {
    private val reTag = Regex("\\b(fhd|uhd|hd|sd|4k|hevc|h265|h264|raw|backup)\\b")
    private val reNon = Regex("[^\\p{L}\\p{N}]")

    fun key(name: String): String {
        var s = name.lowercase()
        s = reTag.replace(s, " ")
        s = reNon.replace(s, "")
        return if (s.isEmpty()) name.lowercase() else s
    }

    /** Isti kanal (po imenu) iz više lista -> jedan kanal + rezervne poveznice. */
    fun dedupe(list: List<Channel>): List<Channel> {
        val map = LinkedHashMap<String, Channel>()
        for (c in list) {
            val k = key(c.name)
            val e = map[k]
            if (e == null) {
                map[k] = c
            } else if (c.url != e.url && !e.alts.contains(c.url)) {
                map[k] = e.copy(
                    alts = e.alts + c.url,
                    logo = if (e.logo.isEmpty()) c.logo else e.logo,
                    tvgId = if (e.tvgId.isEmpty()) c.tvgId else e.tvgId
                )
            }
        }
        return map.values.toList()
    }
}
