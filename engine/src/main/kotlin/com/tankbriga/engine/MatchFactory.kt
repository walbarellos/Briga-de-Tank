package com.tankbriga.engine

/** Creates deterministic local matches for SOLO/mobile testing. */
object MatchFactory {
    const val DEFAULT_SOLO_PLAYERS = 8

    fun createSoloPlayers(terrain: Terrain, count: Int = DEFAULT_SOLO_PLAYERS): MutableList<Tank> {
        val safeCount = count.coerceIn(2, 8)
        val margin = terrain.width * 0.12f
        val playableWidth = terrain.width - margin * 2f
        val players = mutableListOf<Tank>()

        val botNames = listOf("Reaper", "Phantom", "Goliath", "Viper", "Slayer", "Titan", "Shadow")
        val colors = intArrayOf(
            0xFF33B5E5.toInt(), // YOU: Cyan
            0xFFFF4444.toInt(), // Reaper: Red
            0xFFAA66CC.toInt(), // Phantom: Purple
            0xFF99CC00.toInt(), // Goliath: Lime
            0xFFFFBB33.toInt(), // Viper: Orange
            0xFF00DDFF.toInt(), // Slayer: Sky
            0xFFFF8800.toInt(), // Titan: Dark Orange
            0xFF00FF00.toInt()  // Shadow: Green
        )

        repeat(safeCount) { i ->
            val x = margin + playableWidth * (i.toFloat() / (safeCount - 1).toFloat())
            val isHuman = (i == 0)
            val radius = when(i) { 3 -> 18f; 0 -> 15f; else -> 13f }
            val hp = if (i == 3) 125 else 100

            players += Tank(
                id = i.toByte(),
                position = terrain.placeOnSurface(x, radius),
                hp = hp,
                radius = radius,
                name = if (isHuman) "VOCÊ" else botNames.getOrNull(i - 1) ?: "BOT $i",
                isBot = !isHuman,
                color = colors[i % colors.size]
            )
        }
        return players
    }

    /** Creates only the local player for a multiplayer room. */
    fun createMultiplayerLocal(terrain: Terrain, name: String, id: Byte): MutableList<Tank> {
        val x = terrain.width * 0.15f // Initial position for local player
        return mutableListOf(
            Tank(
                id = id,
                position = terrain.placeOnSurface(x, 15f),
                hp = 100,
                radius = 15f,
                name = name,
                isBot = false,
                color = 0xFF33B5E5.toInt()
            )
        )
    }
}
