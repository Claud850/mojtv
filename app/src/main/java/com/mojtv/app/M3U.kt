package com.mojtv.app

data class Channel(val no: Int, val name: String, val group: String, val url: String)

object M3U {
    private fun attr(line: String, key: String): String =
        Regex("$key=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""
        var group = ""
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTINF")) {
                name = l.substringAfterLast(",", "").trim()
                group = attr(l, "group-title")
            } else if (l.startsWith("#EXTGRP:")) {
                group = l.substring(8).trim()
            } else if (!l.startsWith("#")) {
                out.add(Channel(0, name.ifEmpty { l }, group.ifEmpty { "Ostalo" }, l))
                name = ""; group = ""
            }
        }
        return out
    }
}
