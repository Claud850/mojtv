package com.mojtv.app

data class Channel(
    val no: Int, val name: String, val group: String,
    val url: String, val logo: String, val tvgId: String
)

class Parsed(val channels: List<Channel>, val epg: String)

object M3U {
    private val reGroup = Regex("group-title=\"([^\"]*)\"")
    private val reLogo = Regex("tvg-logo=\"([^\"]*)\"")
    private val reId = Regex("tvg-id=\"([^\"]*)\"")
    private val reEpg = Regex("(?:x-tvg-url|url-tvg)=\"([^\"]*)\"")

    private fun f(re: Regex, s: String): String = re.find(s)?.groupValues?.get(1) ?: ""

    fun parse(text: String): Parsed {
        val out = ArrayList<Channel>()
        var epg = ""
        var name = ""
        var group = ""
        var logo = ""
        var id = ""
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTM3U")) {
                epg = f(reEpg, l).substringBefore(",").trim()
            } else if (l.startsWith("#EXTINF")) {
                name = l.substringAfterLast(",", "").trim()
                group = f(reGroup, l)
                logo = f(reLogo, l)
                id = f(reId, l)
            } else if (l.startsWith("#EXTGRP:")) {
                group = l.substring(8).trim()
            } else if (!l.startsWith("#")) {
                out.add(Channel(0, name.ifEmpty { l }, group.ifEmpty { "Ostalo" }, l, logo, id))
                name = ""; group = ""; logo = ""; id = ""
            }
        }
        return Parsed(out, epg)
    }
}
