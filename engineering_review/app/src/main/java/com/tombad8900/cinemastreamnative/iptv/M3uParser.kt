package com.tombad8900.cinemastreamnative.iptv

data class M3uEntry(
    val name: String,
    val url: String,
    val groupName: String = "عام",
    val logo: String = "",
    val tvgId: String = ""
)

object M3uParser {

    private val attributeRegex =
        Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(content: String): List<M3uEntry> {
        val result = mutableListOf<M3uEntry>()

        var pendingName = ""
        var pendingGroup = "عام"
        var pendingLogo = ""
        var pendingTvgId = ""
        var hasMetadata = false

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()

            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    hasMetadata = true

                    val commaIndex = line.indexOf(',')
                    val metadata = if (commaIndex >= 0) {
                        line.substring(0, commaIndex)
                    } else {
                        line
                    }

                    pendingName = if (commaIndex >= 0) {
                        line.substring(commaIndex + 1).trim()
                    } else {
                        ""
                    }

                    val attributes = attributeRegex.findAll(metadata)
                        .associate {
                            it.groupValues[1].lowercase() to it.groupValues[2]
                        }

                    pendingGroup = attributes["group-title"]
                        ?.takeIf { it.isNotBlank() } ?: "عام"

                    pendingLogo = attributes["tvg-logo"] ?: ""
                    pendingTvgId = attributes["tvg-id"] ?: ""
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim()
                        .ifBlank { "عام" }
                }

                line.startsWith("#") -> Unit

                line.contains("://") -> {
                    val channelName = pendingName.ifBlank {
                        line.substringBefore('?')
                            .substringAfterLast('/')
                            .ifBlank { "قناة ${result.size + 1}" }
                    }

                    result.add(
                        M3uEntry(
                            name = channelName,
                            url = line,
                            groupName = pendingGroup,
                            logo = pendingLogo,
                            tvgId = pendingTvgId
                        )
                    )

                    pendingName = ""
                    pendingGroup = "عام"
                    pendingLogo = ""
                    pendingTvgId = ""
                    hasMetadata = false
                }
            }
        }

        return result
    }
}
