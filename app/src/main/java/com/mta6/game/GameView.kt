package com.mta6.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.SurfaceView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), Runnable {

    private enum class GameState { TITLE, PLAYING, BUSTED, VICTORY }

    private enum class VehicleType(
        val title: String,
        val speed: Float,
        val w: Float,
        val h: Float,
        val height3D: Float,
        val bodyColor: Int,
        val accentColor: Int,
        val canFly: Boolean = false,
        val isTank: Boolean = false
    ) {
        NETAJI_SUV("NETAJI FORTUNER", 510f, 88f, 48f, 34f, Color.parseColor("#F5F5F5"), Color.parseColor("#FF6D00")),
        AUTO("DESI AUTO", 410f, 66f, 40f, 32f, Color.parseColor("#FFD600"), Color.parseColor("#00C853")),
        BULLET("BULLET 350", 560f, 56f, 24f, 20f, Color.parseColor("#D50000"), Color.parseColor("#212121")),
        BULLDOZER("BULLDOZER BABA", 370f, 96f, 56f, 42f, Color.parseColor("#FFB300"), Color.parseColor("#263238"), isTank = true),
        CHOPPER("VIP CHOPPER", 620f, 102f, 42f, 48f, Color.parseColor("#1E88E5"), Color.parseColor("#FF6D00"), canFly = true)
    }

    private data class Building(
        val rect: RectF,
        val label: String,
        val subSign: String,
        val wallColor: Int,
        val sideColor: Int,
        val roofColor: Int,
        val height3D: Float,
        val isWashZone: Boolean = false,
        val isSansad: Boolean = false
    )

    private data class PollingBooth(
        val x: Float,
        val y: Float,
        val name: String,
        var votesReady: Int,
        var cooldown: Float = 0f,
        var stealProgress: Float = 0f
    )

    private data class VotePickup(
        var x: Float,
        var y: Float,
        var value: Int,
        var active: Boolean = true
    )

    private data class Vehicle(
        var x: Float,
        var y: Float,
        var angle: Float,
        val type: VehicleType
    )

    private data class PatrolUnit(
        var x: Float,
        var y: Float,
        var angle: Float,
        var speed: Float,
        val isRahulChief: Boolean,
        var hitCooldown: Float = 0f,
        var bubbleText: String = "",
        var bubbleTimer: Float = 0f,
        var knockVx: Float = 0f,
        var knockVy: Float = 0f
    )

    private data class TrafficCar(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int
    )

    private data class CitizenNpc(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val shirtColor: Int,
        var chatTimer: Float = 0f,
        var chatText: String = ""
    )

    private data class DialogueBox(
        val speaker: String,
        val badgeColor: Int,
        val line: String,
        var timer: Float
    )

    // World Grid: Roads at 0, 600, 1200, 1800, 2400, 3000 (width 180 -> ±90)
    // Buildings strictly inside [col*600 + 125 .. col*600 + 475] -> ZERO overlap with roads!
    private val worldW = 3000f
    private val worldH = 3000f
    private val blockStep = 600f
    private val roadWidth = 180f
    private val camAlt = 560f

    // Thread & Audio
    @Volatile private var running = false
    private var gameThread: Thread? = null
    @Volatile private var state = GameState.TITLE
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastVoiceTime = 0L

    // Player & Economy
    private var playerX = 1200f
    private var playerY = 1200f
    private var playerAngle = 0f
    private var walkPhase = 0f
    private var playerHealth = 100f
    private var votes = 0
    private var totalStolenBooths = 0
    private var wantedStars = 0
    private var wantedDecayTimer = 0f
    private var currentVehicle: Vehicle? = null
    private var chopperAltitude = 0f
    private var washCooldown = 0f
    private var playerBubble = ""
    private var playerBubbleTimer = 0f

    // Story Missions (GTA 6 Style 5 Chapters)
    // 1: Chai Pe Charcha, 2: EVM Express, 3: Aaloo Se Sona, 4: Washing Machine Escape, 5: Sansad Finale, 6: Free Roam King
    private var missionStage = 1
    private var missionTitle = "CH 1: CHAI PE CHARCHA"
    private var missionGoalText = "Chai Tapri (600, 600) jao aur Votes lo!"
    private var missionTargetX = 600f
    private var missionTargetY = 600f
    private var missionSubStep = 0
    private var missionTimer = 0f
    private var hasGoldenPotato = false

    // Camera & Effects
    private var camX = 1200f
    private var camY = 1200f
    private var screenShake = 0f
    private var bannerText = ""
    private var bannerColor = Color.parseColor("#FFAB00")
    private var bannerTimer = 0f
    private var worldTime = 0f
    private var activeDialogue: DialogueBox? = null

    // Entities
    private val buildings = mutableListOf<Building>()
    private val booths = mutableListOf<PollingBooth>()
    private val pickups = mutableListOf<VotePickup>()
    private val vehicles = mutableListOf<Vehicle>()
    private val patrols = mutableListOf<PatrolUnit>()
    private val traffic = mutableListOf<TrafficCar>()
    private val citizens = mutableListOf<CitizenNpc>()

    // Multi-Touch Controls (Floating Left Joystick + Right Action Cluster)
    @Volatile private var joyPointerId = -1
    @Volatile private var joyOriginX = 185f
    @Volatile private var joyOriginY = 520f
    @Volatile private var joyDx = 0f
    @Volatile private var joyDy = 0f
    @Volatile private var stealHeld = false
    @Volatile private var nitroHeld = false
    @Volatile private var rideRequested = false
    @Volatile private var memeRequested = false

    private var defaultJoyX = 185f
    private var defaultJoyY = 520f
    private var joyRadius = 110f

    private var btnStealX = 1000f
    private var btnStealY = 540f
    private var btnRideX = 1150f
    private var btnRideY = 420f
    private var btnNitroX = 1155f
    private var btnNitroY = 570f
    private var btnMemeX = 995f
    private var btnMemeY = 395f
    private val btnRadius = 62f

    // Meme Dialogue Packs
    private val modiMemes = listOf(
        "Mitron! Aaj Votes ki baarish hogi!",
        "Sabka Saath, Full Nitro Vikas!",
        "Aaloo daalo, Sona nikaalo!",
        "Kya baat hai! 56 inch speed!",
        "Chai garam hai, rally shuru karo!",
        "Wah bhai wah! Full pro driving!"
    )

    private val rahulPoliceMemes = listOf(
        "DARO MAT! POLICE AA GAYI!",
        "KHATAM, TATA, BYE BYE!",
        "BHARAT JODO CHASE SHURU!",
        "VOTE CHOR RUK JAO!",
        "MOHABBAT KI DUKAAN OPEN!",
        "EVM CHECK KARNA PADEGA!"
    )

    private val publicMemes = listOf(
        "Selfie please!", "Mitron!", "Chai lao!", "Vote kisko du?", "GTA 6 India!"
    )

    // Reusable Graphics Objects
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wallPath = Path()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("hi", "IN"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("en", "IN"))
                }
                tts?.setSpeechRate(1.12f)
                ttsReady = true
            }
        }
        buildIndian3DWorld()
    }

    private fun speakMeme(text: String, force: Boolean = false) {
        if (!ttsReady) return
        val now = System.currentTimeMillis()
        if (!force && now - lastVoiceTime < 2600L) return
        lastVoiceTime = now
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mta6_voice")
    }

    private fun buildIndian3DWorld() {
        buildings.clear()
        booths.clear()
        pickups.clear()
        vehicles.clear()
        patrols.clear()
        traffic.clear()
        citizens.clear()

        val landmarkData = listOf(
            Triple("CHAI TAPRI", "24x7 CUTTING", 130f),
            Triple("GUJARAT PLAZA", "AALOO-SONA LAB", 205f),
            Triple("PINK BAZAAR", "JAIPUR HERITAGE", 165f),
            Triple("DILLI HAAT", "CHAAT CORNER", 150f),
            Triple("WASHING ZONE", "0★ CLEANER", 145f),

            Triple("MUMBAI STUDIOS", "BOLLYWOOD HQ", 220f),
            Triple("LAL CHOWK", "MAIN MARKET", 170f),
            Triple("KISAN MANDI", "FRESH MARKET", 140f),
            Triple("METRO DEPOT", "FAST LINE", 160f),
            Triple("IT CELL TOWER", "WIFI 10G", 235f),

            Triple("INDIA GATE PARK", "SELFIE POINT", 125f),
            Triple("MEDIA HOUSE", "BREAKING NEWS", 210f),
            Triple("SANSAD BHAVAN", "HELIPAD [H]", 240f),
            Triple("RALLY GROUND", "CROWD ZONE", 135f),
            Triple("UP EXPRESS HUB", "BULLDOZER YARD", 180f),

            Triple("SAMOSA JUNCTION", "GARAM SAMOSA", 135f),
            Triple("VIP BUNGLOW", "Z+ SECURITY", 175f),
            Triple("CRICKET STADIUM", "IPL NIGHTS", 195f),
            Triple("TECH CYBER HUB", "STARTUP ZONE", 225f),
            Triple("DHABA 24x7", "MAKKHAN PARATHA", 130f),

            Triple("WASHING ZONE", "0★ CLEANER", 145f),
            Triple("OLD BAZAAR", "SPICE MARKET", 155f),
            Triple("BUS ADDA", "EXPRESS DEPOT", 140f),
            Triple("MARINE TOWERS", "SEA BREEZE", 230f),
            Triple("ELECTION TOWER", "EVM VAULT", 215f)
        )

        val wallPalette = listOf(
            Pair(Color.parseColor("#E06D53"), Color.parseColor("#B84A39")),
            Pair(Color.parseColor("#D45D79"), Color.parseColor("#A43D58")),
            Pair(Color.parseColor("#546E7A"), Color.parseColor("#37474F")),
            Pair(Color.parseColor("#8D6E63"), Color.parseColor("#5D4037")),
            Pair(Color.parseColor("#5C6BC0"), Color.parseColor("#3949AB")),
            Pair(Color.parseColor("#F57C00"), Color.parseColor("#BF360C"))
        )

        var idx = 0
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                val bx = col * blockStep + 125f
                val by = row * blockStep + 125f
                val bw = 350f
                val bh = 350f
                val (name, sub, h3d) = landmarkData[idx]
                val isWash = name == "WASHING ZONE"
                val isSansad = name == "SANSAD BHAVAN"

                val (wCol, sCol) = when {
                    isWash -> Pair(Color.parseColor("#00BCD4"), Color.parseColor("#00838F"))
                    isSansad -> Pair(Color.parseColor("#D8A46F"), Color.parseColor("#A67443"))
                    else -> wallPalette[idx % wallPalette.size]
                }
                val rCol = when {
                    isWash -> Color.parseColor("#4DD0E1")
                    isSansad -> Color.parseColor("#FFAB00")
                    else -> Color.parseColor("#263238")
                }

                buildings.add(
                    Building(
                        rect = RectF(bx, by, bx + bw, by + bh),
                        label = name,
                        subSign = sub,
                        wallColor = wCol,
                        sideColor = sCol,
                        roofColor = rCol,
                        height3D = h3d,
                        isWashZone = isWash,
                        isSansad = isSansad
                    )
                )
                idx++
            }
        }

        // 8 Polling Booths placed cleanly on sidewalk corners (NEVER inside buildings!)
        val boothSpots = listOf(
            Triple(695f, 695f, "BOOTH #1 DILLI"),
            Triple(1295f, 695f, "BOOTH #2 GUJARAT"),
            Triple(1895f, 695f, "BOOTH #3 JAIPUR"),
            Triple(2305f, 1295f, "BOOTH #4 UP"),
            Triple(695f, 1295f, "BOOTH #5 MUMBAI"),
            Triple(1895f, 1895f, "BOOTH #6 BIHAR"),
            Triple(1295f, 2305f, "BOOTH #7 PUNJAB"),
            Triple(2305f, 2305f, "BOOTH #8 BENGAL")
        )
        for ((bx, by, bName) in boothSpots) {
            booths.add(PollingBooth(bx, by, bName, votesReady = 1000))
        }

        // Street Vote Bundles strictly along roads
        for (i in 0 until 24) {
            val onHoriz = i % 2 == 0
            val px = if (onHoriz) Random.nextFloat() * 2600f + 200f else (Random.nextInt(1, 5) * blockStep)
            val py = if (onHoriz) (Random.nextInt(1, 5) * blockStep) else Random.nextFloat() * 2600f + 200f
            pickups.add(VotePickup(px, py, value = 250))
        }

        // All Indian 3D Vehicles parked strictly on ROADS!
        val rides = listOf(
            Triple(1200f, 1085f, VehicleType.NETAJI_SUV),
            Triple(1085f, 1200f, VehicleType.AUTO),
            Triple(1315f, 1200f, VehicleType.BULLET),
            Triple(1200f, 1320f, VehicleType.BULLDOZER),
            Triple(1800f, 1200f, VehicleType.CHOPPER),
            Triple(600f, 1100f, VehicleType.NETAJI_SUV),
            Triple(1200f, 520f, VehicleType.AUTO),
            Triple(1800f, 1730f, VehicleType.BULLDOZER),
            Triple(2400f, 680f, VehicleType.BULLET),
            Triple(600f, 2320f, VehicleType.NETAJI_SUV),
            Triple(2400f, 2340f, VehicleType.CHOPPER)
        )
        for ((vx, vy, vType) in rides) {
            vehicles.add(Vehicle(vx, vy, angle = 0f, type = vType))
        }

        // Police Squad led by Police Chief RAHUL G!
        patrols.add(PatrolUnit(600f, 1200f, 0f, 305f, isRahulChief = true))
        patrols.add(PatrolUnit(1800f, 600f, 0f, 290f, isRahulChief = false))
        patrols.add(PatrolUnit(600f, 2400f, 0f, 315f, isRahulChief = false))
        patrols.add(PatrolUnit(2400f, 2400f, 0f, 330f, isRahulChief = false))

        // Living City Traffic on Roads
        val trafficColors = listOf(
            Color.parseColor("#FFD600"),
            Color.parseColor("#EC407A"),
            Color.parseColor("#29B6F6"),
            Color.parseColor("#AB47BC"),
            Color.parseColor("#66BB6A")
        )
        for (i in 0 until 6) {
            val horiz = i % 2 == 0
            val lane = ((i % 4) + 1) * blockStep
            val tx = if (horiz) Random.nextFloat() * 2600f + 200f else lane + 40f
            val ty = if (horiz) lane - 40f else Random.nextFloat() * 2600f + 200f
            val spd = if (i % 3 == 0) -170f else 170f
            traffic.add(
                TrafficCar(
                    tx, ty,
                    vx = if (horiz) spd else 0f,
                    vy = if (horiz) 0f else spd,
                    color = trafficColors[i % trafficColors.size]
                )
            )
        }

        // Walking Public Crowd on Sidewalks
        val npcColors = listOf(
            Color.parseColor("#FF7043"),
            Color.parseColor("#42A5F5"),
            Color.parseColor("#FFCA28"),
            Color.parseColor("#EC407A"),
            Color.parseColor("#26A69A")
        )
        for (i in 0 until 14) {
            val roadLine = Random.nextInt(1, 5) * blockStep
            val alongX = i % 2 == 0
            val nx = if (alongX) Random.nextFloat() * 2600f + 200f else roadLine + 96f
            val ny = if (alongX) roadLine + 96f else Random.nextFloat() * 2600f + 200f
            val dir = if (Random.nextBoolean()) 48f else -48f
            citizens.add(
                CitizenNpc(
                    nx, ny,
                    vx = if (alongX) dir else 0f,
                    vy = if (alongX) 0f else dir,
                    shirtColor = npcColors[i % npcColors.size]
                )
            )
        }
    }

    private fun startNewSession(keepProgress: Boolean) {
        if (!keepProgress) {
            votes = 0
            totalStolenBooths = 0
            missionStage = 1
            missionSubStep = 0
            hasGoldenPotato = false
            buildIndian3DWorld()
        } else {
            votes = (votes * 0.85f).toInt()
        }

        // Spawn cleanly on open 4-way road intersection!
        playerX = 1200f
        playerY = 1200f
        playerAngle = 0f
        playerHealth = 100f
        wantedStars = if (keepProgress && missionStage == 4) 3 else 0
        wantedDecayTimer = 0f
        currentVehicle = null
        chopperAltitude = 0f
        camX = playerX
        camY = playerY
        state = GameState.PLAYING

        setupCurrentMission()
    }

    private fun setupCurrentMission() {
        when (missionStage) {
            1 -> {
                missionTitle = "CH 1: CHAI PE CHARCHA"
                missionGoalText = "Yellow GPS follow karke CHAI TAPRI (600, 600) pahuncho!"
                missionTargetX = 600f
                missionTargetY = 600f
                showDialogue("MODI JI", "#FF6D00", "Mitron! Aaj se MTA 6 Election Mission shuru! Pehle Chai Tapri chalo!", 4.5f)
                speakMeme("Mitron! Aaj se mission shuru! Pehle Chai Tapri chalo!", force = true)
            }
            2 -> {
                missionTitle = "CH 2: EVM EXPRESS"
                missionGoalText = "Booth #1 par 'CHORI' dabao aur RAHUL G Police se bacho!"
                missionTargetX = 695f
                missionTargetY = 695f
                missionSubStep = 0
                missionTimer = 0f
                showDialogue("CHAI WALA", "#FFD600", "Sirji! Booth #1 par 1000 Votes ready hain, par Police Chief Rahul G alert par hain!", 4.8f)
                speakMeme("Booth number ek par hazaar votes ready hain!", force = true)
            }
            3 -> {
                missionTitle = "CH 3: AALOO SE SONA"
                missionSubStep = 0
                hasGoldenPotato = false
                missionGoalText = "Kisan Mandi (1200, 1800) se GOLDEN AALOO uthao!"
                missionTargetX = 1200f
                missionTargetY = 1800f
                showDialogue("MODI JI", "#FF6D00", "Mission 3: Kisan Mandi se Golden Aaloo utha kar Gujarat Plaza deliver karna hai!", 4.8f)
                speakMeme("Kisan Mandi se Golden Aaloo utha kar Gujarat Plaza chalo!", force = true)
            }
            4 -> {
                missionTitle = "CH 4: WASHING MACHINE"
                missionGoalText = "3★ RAHUL G Squad peeche hai! Cyan WASHING ZONE (2400, 600) bhago!"
                missionTargetX = 2400f
                missionTargetY = 600f
                wantedStars = 3
                wantedDecayTimer = 60f
                showDialogue("POLICE CHIEF RAHUL G", "#2979FF", "Daro Mat! Bharat Jodo Police aa rahi hai! Khatam, Tata, Bye Bye!", 5.0f)
                speakMeme("Daro mat! Police aa rahi hai! Khatam tata bye bye!", force = true)
            }
            5 -> {
                missionTitle = "CH 5: SANSAD FINALE"
                missionSubStep = 0
                missionGoalText = "VIP CHOPPER (1800, 1200) udao aur SANSAD BHAVAN roof par land karo!"
                missionTargetX = 1800f
                missionTargetY = 1200f
                showDialogue("BREAKING NEWS", "#FF2E4D", "GRAND FINALE! VIP Chopper lekar seedha Sansad Bhavan Helipad par land karo!", 5.2f)
                speakMeme("Grand Finale! Helicopter lekar Sansad Bhavan par land karo!", force = true)
            }
            else -> {
                missionTitle = "PM FREE ROAM KING"
                missionGoalText = "Open World Unlocked! Unlimited Votes, Chopper & Bulldozer!"
                missionTargetX = -1f
                missionTargetY = -1f
            }
        }
    }

    private fun showDialogue(speaker: String, hex: String, line: String, secs: Float = 4.2f) {
        activeDialogue = DialogueBox(speaker, Color.parseColor(hex), line, secs)
    }

    private fun showBanner(msg: String, hex: String, duration: Float = 2.4f) {
        bannerText = msg
        bannerColor = Color.parseColor(hex)
        bannerTimer = duration
    }

    fun resumeGame() {
        if (running) return
        running = true
        gameThread = Thread(this)
        gameThread?.start()
    }

    fun pauseGame() {
        running = false
        tts?.stop()
        try {
            gameThread?.join(500)
        } catch (_: InterruptedException) {
        }
        gameThread = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tts?.stop()
        tts?.shutdown()
    }

    override fun run() {
        var lastNs = System.nanoTime()
        while (running) {
            val nowNs = System.nanoTime()
            val dt = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastNs = nowNs

            if (width > 0 && height > 0) {
                layoutTouchControls()
                if (state == GameState.PLAYING) {
                    updateGame(dt)
                }
                renderFrame()
            } else {
                try {
                    Thread.sleep(16)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun layoutTouchControls() {
        val w = width.toFloat()
        val h = height.toFloat()
        joyRadius = (h * 0.145f).coerceIn(82f, 125f)
        defaultJoyX = joyRadius + 55f
        defaultJoyY = h - joyRadius - 42f
        if (joyPointerId == -1) {
            joyOriginX = defaultJoyX
            joyOriginY = defaultJoyY
        }

        btnStealX = w - 230f
        btnStealY = h - 110f
        btnMemeX = w - 225f
        btnMemeY = h - 245f
        btnRideX = w - 95f
        btnRideY = h - 235f
        btnNitroX = w - 95f
        btnNitroY = h - 98f
    }

    // ==================== GAME LOGIC ====================

    private fun updateGame(dt: Float) {
        worldTime += dt
        if (bannerTimer > 0f) bannerTimer -= dt
        if (washCooldown > 0f) washCooldown -= dt
        if (playerBubbleTimer > 0f) playerBubbleTimer -= dt
        if (screenShake > 0f) screenShake = (screenShake - dt * 28f).coerceAtLeast(0f)

        activeDialogue?.let { dlg ->
            dlg.timer -= dt
            if (dlg.timer <= 0f) activeDialogue = null
        }

        // 1. Random Funny Meme Button
        if (memeRequested) {
            memeRequested = false
            val line = modiMemes[Random.nextInt(modiMemes.size)]
            playerBubble = line
            playerBubbleTimer = 3.0f
            showBanner(line, "#CCFF00", 2.2f)
            speakMeme(line, force = true)
        }

        // 2. Enter / Exit Vehicle (Including VIP Chopper!)
        if (rideRequested) {
            rideRequested = false
            if (currentVehicle != null) {
                if (currentVehicle!!.type.canFly && collidesBuilding(playerX, playerY, 22f)) {
                    showBanner("Road par aakar land karo!", "#FF5252", 1.8f)
                } else {
                    currentVehicle = null
                    chopperAltitude = 0f
                    showBanner("ON FOOT MODE", "#CCFF00", 1.5f)
                }
            } else {
                val nearest = vehicles.minByOrNull { hypot(it.x - playerX, it.y - playerY) }
                if (nearest != null && hypot(nearest.x - playerX, nearest.y - playerY) < 130f) {
                    currentVehicle = nearest
                    showBanner("${nearest.type.title} READY!", "#FFAB00", 2.0f)
                    if (nearest.type.isTank) {
                        speakMeme("Bulldozer Baba on road!", force = true)
                    } else if (nearest.type.canFly) {
                        speakMeme("VIP Helicopter takeoff!", force = true)
                    }
                } else {
                    showBanner("Koi gaadi paas nahi hai!", "#FF5252", 1.5f)
                }
            }
        }

        // Smooth Chopper takeoff/landing height
        val isFlying = currentVehicle?.type?.canFly == true
        chopperAltitude = if (isFlying) {
            (chopperAltitude + dt * 260f).coerceAtMost(210f)
        } else {
            (chopperAltitude - dt * 320f).coerceAtLeast(0f)
        }

        // 3. Player / Vehicle Movement (With Anti-Stuck Guarantee)
        val mag = hypot(joyDx, joyDy)
        if (mag > 0.06f) {
            val baseSpeed = currentVehicle?.type?.speed ?: 265f
            val boost = if (nitroHeld) 1.52f else 1.0f
            val moveSpeed = baseSpeed * boost * mag.coerceAtMost(1f)
            val vx = (joyDx / mag) * moveSpeed * dt
            val vy = (joyDy / mag) * moveSpeed * dt
            playerAngle = atan2(joyDy, joyDx)
            walkPhase += dt * (if (nitroHeld) 15f else 10f)

            if (isFlying && chopperAltitude > 90f) {
                // Chopper flies freely OVER 3D buildings!
                playerX = (playerX + vx).coerceIn(70f, worldW - 70f)
                playerY = (playerY + vy).coerceIn(70f, worldH - 70f)
            } else {
                val hitRadius = if (currentVehicle != null) 26f else 16f
                moveWithCollision(vx, vy, hitRadius)
            }

            currentVehicle?.let { v ->
                v.x = playerX
                v.y = playerY
                v.angle = playerAngle
            }
        }

        // Anti-stuck safeguard (never trapped inside a building on foot/car)
        if (!isFlying && collidesBuilding(playerX, playerY, 14f)) {
            playerX = (round(playerX / blockStep) * blockStep).coerceIn(600f, 2400f)
            playerY = (round(playerY / blockStep) * blockStep).coerceIn(600f, 2400f)
        }

        // Smooth Dynamic GTA Camera
        camX += (playerX - camX) * (dt * 7.5f).coerceAtMost(1f)
        camY += (playerY - camY) * (dt * 7.5f).coerceAtMost(1f)

        // 4. Living Traffic & Public Crowd Update
        updateTrafficAndCitizens(dt)

        // 5. Collect Street Vote Bundles
        for (p in pickups) {
            if (p.active && chopperAltitude < 60f && hypot(p.x - playerX, p.y - playerY) < 52f) {
                p.active = false
                votes += p.value
                showBanner("+${p.value} VOTES!", "#00E676", 1.3f)
            }
        }
        if (pickups.count { it.active } < 8) {
            for (p in pickups) {
                if (!p.active) {
                    p.x = (Random.nextInt(1, 5) * blockStep) + Random.nextFloat() * 50f - 25f
                    p.y = Random.nextFloat() * 2500f + 250f
                    p.active = true
                }
            }
        }

        // 6. Polling Booth Vote Chori
        for (booth in booths) {
            if (booth.cooldown > 0f) {
                booth.cooldown -= dt
                if (booth.cooldown <= 0f) booth.votesReady = 1000 + Random.nextInt(0, 5) * 100
                continue
            }
            val dist = hypot(booth.x - playerX, booth.y - playerY)
            if (dist < 115f && chopperAltitude < 40f && stealHeld) {
                booth.stealProgress += dt * 0.90f
                if (booth.stealProgress >= 1f) {
                    val loot = booth.votesReady
                    votes += loot
                    totalStolenBooths++
                    booth.votesReady = 0
                    booth.stealProgress = 0f
                    booth.cooldown = 24f
                    wantedStars = (wantedStars + 1).coerceAtMost(5)
                    wantedDecayTimer = 26f
                    screenShake = 8f
                    showBanner("VOTE CHOR ALERT! +$loot VOTES ★$wantedStars", "#FF3D00", 2.8f)
                    speakMeme("Vote Chor Alert! Police Chief Rahul aa rahe hain!")

                    // Mission 2 trigger
                    if (missionStage == 2 && missionSubStep == 0) {
                        missionSubStep = 1
                        missionTimer = 10f
                        missionGoalText = "RAHUL G Police se 10 sec bacho! (${missionTimer.toInt()}s)"
                        showDialogue("POLICE CHIEF RAHUL G", "#2979FF", "Arey ruko! Vote chori pakdi gayi! Khatam, Tata, Bye Bye!", 4.0f)
                    }
                }
            } else {
                booth.stealProgress = (booth.stealProgress - dt * 0.5f).coerceAtLeast(0f)
            }
        }

        // 7. Washing Zone Check (Works right from the road beside Washing Zone!)
        if (wantedStars > 0 && washCooldown <= 0f) {
            for (b in buildings) {
                if (b.isWashZone) {
                    val nearWash = playerX in (b.rect.left - 135f)..(b.rect.right + 135f) &&
                        playerY in (b.rect.top - 135f)..(b.rect.bottom + 135f)
                    if (nearWash) {
                        wantedStars = 0
                        playerHealth = 100f
                        washCooldown = 8f
                        showBanner("WASHING MACHINE CLEAN! 0★ SAB CLEAR!", "#00E5FF", 3.2f)
                        speakMeme("Washing machine clean! Saare cases khatam!", force = true)
                        if (missionStage == 4) {
                            completeCurrentMission(2500)
                        }
                        break
                    }
                }
            }
        }

        // 8. Story Mission Progression Engine
        updateStoryMissions(dt)

        // 9. Police Chief RAHUL G & Patrol AI Chase
        updatePoliceChase(dt)
    }

    private fun updateStoryMissions(dt: Float) {
        when (missionStage) {
            1 -> {
                if (hypot(playerX - missionTargetX, playerY - missionTargetY) < 120f) {
                    completeCurrentMission(1000)
                }
            }
            2 -> {
                if (missionSubStep == 1) {
                    missionTimer -= dt
                    missionGoalText = "RAHUL G Police se bacho! (${missionTimer.coerceAtLeast(0f).toInt()}s)"
                    if (missionTimer <= 0f) {
                        completeCurrentMission(1500)
                    }
                }
            }
            3 -> {
                if (missionSubStep == 0 && hypot(playerX - missionTargetX, playerY - missionTargetY) < 110f) {
                    missionSubStep = 1
                    hasGoldenPotato = true
                    wantedStars = wantedStars.coerceAtLeast(2)
                    wantedDecayTimer = 35f
                    missionTargetX = 1200f
                    missionTargetY = 600f
                    missionGoalText = "GOLDEN AALOO mil gaya! Gujarat Plaza (1200, 600) deliver karo!"
                    showDialogue("MODI JI", "#FF6D00", "Golden Aaloo lock ho gaya! Ab seedha Gujarat Plaza bhagao!", 4.0f)
                    speakMeme("Golden Aaloo mil gaya! Gujarat Plaza chalo!", force = true)
                } else if (missionSubStep == 1 && hypot(playerX - missionTargetX, playerY - missionTargetY) < 120f) {
                    hasGoldenPotato = false
                    completeCurrentMission(2000)
                }
            }
            5 -> {
                val sansadCenterX = 1500f
                val sansadCenterY = 1500f
                if (missionSubStep == 0) {
                    if (currentVehicle?.type == VehicleType.CHOPPER) {
                        missionSubStep = 1
                        missionTargetX = sansadCenterX
                        missionTargetY = sansadCenterY
                        missionGoalText = "Chopper seedha SANSAD BHAVAN [H] roof par le jao!"
                        showBanner("CHOPPER AIRBORNE! FLY TO SANSAD [H]!", "#CCFF00", 3.0f)
                    }
                } else if (missionSubStep == 1) {
                    if (hypot(playerX - sansadCenterX, playerY - sansadCenterY) < 140f) {
                        votes += 5000
                        missionStage = 6
                        state = GameState.VICTORY
                        speakMeme("Badhai ho! Election jeet gaye! Ab aap PM Free Roam King hain!", force = true)
                    }
                }
            }
        }
    }

    private fun completeCurrentMission(rewardVotes: Int) {
        votes += rewardVotes
        showBanner("MISSION PASSED! +$rewardVotes VOTES!", "#CCFF00", 3.2f)
        speakMeme("Mission passed! Plus $rewardVotes votes!", force = true)
        missionStage++
        setupCurrentMission()
    }

    private fun updateTrafficAndCitizens(dt: Float) {
        for (car in traffic) {
            car.x += car.vx * dt
            car.y += car.vy * dt
            if (car.x < 100f) car.x = worldW - 100f
            if (car.x > worldW - 100f) car.x = 100f
            if (car.y < 100f) car.y = worldH - 100f
            if (car.y > worldH - 100f) car.y = 100f
        }
        for (npc in citizens) {
            npc.x += npc.vx * dt
            npc.y += npc.vy * dt
            if (npc.x < 150f || npc.x > worldW - 150f) npc.vx = -npc.vx
            if (npc.y < 150f || npc.y > worldH - 150f) npc.vy = -npc.vy

            if (npc.chatTimer > 0f) {
                npc.chatTimer -= dt
            } else if (hypot(npc.x - playerX, npc.y - playerY) < 180f && Random.nextFloat() < 0.015f) {
                npc.chatText = publicMemes[Random.nextInt(publicMemes.size)]
                npc.chatTimer = 2.5f
            }
        }
    }

    private fun updatePoliceChase(dt: Float) {
        if (wantedStars <= 0) {
            playerHealth = (playerHealth + dt * 7f).coerceAtMost(100f)
            return
        }

        wantedDecayTimer -= dt
        if (wantedDecayTimer <= 0f && missionStage != 4) {
            wantedStars--
            wantedDecayTimer = 20f
        }

        val activeChasers = wantedStars.coerceAtMost(patrols.size)
        for (i in 0 until activeChasers) {
            val cop = patrols[i]
            if (cop.hitCooldown > 0f) cop.hitCooldown -= dt
            if (cop.bubbleTimer > 0f) cop.bubbleTimer -= dt

            // Apply Bulldozer knockback decay
            if (abs(cop.knockVx) > 2f || abs(cop.knockVy) > 2f) {
                cop.x = (cop.x + cop.knockVx * dt).coerceIn(70f, worldW - 70f)
                cop.y = (cop.y + cop.knockVy * dt).coerceIn(70f, worldH - 70f)
                cop.knockVx *= 0.88f
                cop.knockVy *= 0.88f
                continue
            }

            val dx = playerX - cop.x
            val dy = playerY - cop.y
            val dist = hypot(dx, dy).coerceAtLeast(1f)

            // Random Rahul G Meme Shout while chasing!
            if (cop.isRahulChief && dist < 520f && cop.bubbleTimer <= 0f) {
                cop.bubbleText = rahulPoliceMemes[Random.nextInt(rahulPoliceMemes.size)]
                cop.bubbleTimer = 3.6f
                if (Random.nextFloat() < 0.45f) {
                    speakMeme(cop.bubbleText)
                }
            }

            if (dist > 1450f) {
                val spawnAngle = Random.nextFloat() * (2f * PI.toFloat())
                cop.x = (playerX + cos(spawnAngle) * 760f).coerceIn(120f, worldW - 120f)
                cop.y = (playerY + sin(spawnAngle) * 760f).coerceIn(120f, worldH - 120f)
            } else {
                val chaseSpeed = (cop.speed + wantedStars * 24f).coerceAtMost(440f)
                cop.angle = atan2(dy, dx)
                cop.x = (cop.x + (dx / dist) * chaseSpeed * dt).coerceIn(60f, worldW - 60f)
                cop.y = (cop.y + (dy / dist) * chaseSpeed * dt).coerceIn(60f, worldH - 60f)

                if (dist < 48f && chopperAltitude < 60f && cop.hitCooldown <= 0f) {
                    if (currentVehicle?.type?.isTank == true) {
                        // Bulldozer smashes police away!
                        cop.knockVx = -(dx / dist) * 650f
                        cop.knockVy = -(dy / dist) * 650f
                        cop.hitCooldown = 1.2f
                        screenShake = 10f
                        showBanner("BULLDOZER SMASH! POLICE FLUNG!", "#FFB300", 1.8f)
                    } else {
                        val dmg = if (currentVehicle != null) 9f else 17f
                        playerHealth -= dmg
                        cop.hitCooldown = 1.15f
                        screenShake = 9f
                        val hitMsg = if (cop.isRahulChief) "RAHUL G POLICE HIT! WASHING ZONE BHAGO!" else "EC SQUAD HIT!"
                        showBanner(hitMsg, "#FF1744", 1.8f)
                        if (playerHealth <= 0f) {
                            playerHealth = 0f
                            state = GameState.BUSTED
                            speakMeme("Busted! Khatam, tata, bye bye!", force = true)
                        }
                    }
                }
            }
        }
    }

    private fun moveWithCollision(dx: Float, dy: Float, radius: Float) {
        val nextX = (playerX + dx).coerceIn(60f, worldW - 60f)
        if (!collidesBuilding(nextX, playerY, radius)) {
            playerX = nextX
        }
        val nextY = (playerY + dy).coerceIn(60f, worldH - 60f)
        if (!collidesBuilding(playerX, nextY, radius)) {
            playerY = nextY
        }
    }

    private fun collidesBuilding(x: Float, y: Float, r: Float): Boolean {
        for (b in buildings) {
            if (x + r > b.rect.left && x - r < b.rect.right &&
                y + r > b.rect.top && y - r < b.rect.bottom
            ) {
                return true
            }
        }
        return false
    }

    // ==================== 3D EXTRUDED PERSPECTIVE RENDER ENGINE ====================

    private fun projX(wx: Float, z: Float, halfW: Float): Float {
        val scale = camAlt / (camAlt - z).coerceAtLeast(140f)
        return halfW + (wx - camX) * scale
    }

    private fun projY(wy: Float, z: Float, halfH: Float): Float {
        val scale = camAlt / (camAlt - z).coerceAtLeast(140f)
        return halfH + (wy - camY) * scale
    }

    private fun renderFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            val w = width.toFloat()
            val h = height.toFloat()
            val halfW = w * 0.5f + if (screenShake > 0f) (Random.nextFloat() - 0.5f) * screenShake else 0f
            val halfH = h * 0.5f + if (screenShake > 0f) (Random.nextFloat() - 0.5f) * screenShake else 0f

            // GTA 6 Vice-Desi Twilight Ground
            canvas.drawColor(Color.parseColor("#1D242B"))

            // Ground Layer (z = 0)
            canvas.save()
            canvas.translate(halfW - camX, halfH - camY)
            drawRoadsAndSidewalks(canvas)
            drawMissionGroundBeacon(canvas)
            drawPollingBooths(canvas)
            drawVotePickups(canvas)
            drawCitizens(canvas)
            drawTraffic(canvas)
            canvas.restore()

            // Ground-Level 3D Vehicles, Player & Police (if not flying above buildings)
            drawAllVehicles3D(canvas, halfW, halfH, flyingOnly = false)
            if (currentVehicle == null) {
                drawPlayer3D(canvas, halfW, halfH)
            }
            drawPatrols3D(canvas, halfW, halfH)

            // True 3D Extruded Perspective Buildings (Depth-sorted painter's algorithm!)
            drawBuildings3D(canvas, halfW, halfH)

            // Flying 3D Helicopter renders ABOVE all 3D rooftops!
            drawAllVehicles3D(canvas, halfW, halfH, flyingOnly = true)

            // GPS Direction Arrow around Player
            if (state == GameState.PLAYING && missionTargetX > 0f) {
                drawGpsArrow(canvas, halfW, halfH)
            }

            // HUD, MiniMap, Story Dialogue & Controls
            drawHUD(canvas, w, h)
            drawMiniMap(canvas, w)

            if (state == GameState.PLAYING) {
                drawTouchControls(canvas)
                drawDialogueOverlay(canvas, w, h)
            } else if (state == GameState.TITLE) {
                drawTitleOverlay(canvas, w, h)
            } else if (state == GameState.BUSTED) {
                drawEndOverlay(canvas, w, h, victory = false)
            } else if (state == GameState.VICTORY) {
                drawEndOverlay(canvas, w, h, victory = true)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawRoadsAndSidewalks(canvas: Canvas) {
        // Sidewalk borders around city blocks
        paint.color = Color.parseColor("#37404A")
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                val x = col * blockStep + 92f
                val y = row * blockStep + 92f
                canvas.drawRoundRect(RectF(x, y, x + 416f, y + 416f), 22f, 22f, paint)
            }
        }

        // Asphalt Roads
        paint.color = Color.parseColor("#181A20")
        for (i in 0..5) {
            val pos = i * blockStep
            canvas.drawRect(pos - roadWidth * 0.5f, 0f, pos + roadWidth * 0.5f, worldH, paint)
            canvas.drawRect(0f, pos - roadWidth * 0.5f, worldW, pos + roadWidth * 0.5f, paint)
        }

        // Neon Yellow Lane Dashes
        paint.color = Color.parseColor("#FFD600")
        for (i in 0..5) {
            val pos = i * blockStep
            var d = 0f
            while (d < worldW) {
                canvas.drawRect(pos - 3f, d, pos + 3f, d + 34f, paint)
                canvas.drawRect(d, pos - 3f, d + 34f, pos + 3f, paint)
                d += 74f
            }
        }

        // Glowing Cyan Wash Pads on the roads beside Washing Zones
        for (b in buildings) {
            if (b.isWashZone) {
                val padX = if (b.rect.left > 1500f) 2400f else 600f
                val padY = if (b.rect.top > 1500f) 2400f else 600f
                val pulse = 55f + sin(worldTime * 5f) * 8f
                paint.color = Color.argb(95, 0, 229, 255)
                canvas.drawCircle(padX, padY, pulse, paint)
                strokePaint.color = Color.parseColor("#00E5FF")
                strokePaint.strokeWidth = 4f
                canvas.drawCircle(padX, padY, pulse, strokePaint)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = Color.parseColor("#00E5FF")
                textPaint.textSize = 18f
                textPaint.isFakeBoldText = true
                canvas.drawText("WASH 0★", padX, padY + 6f, textPaint)
            }
        }
    }

    private fun drawMissionGroundBeacon(canvas: Canvas) {
        if (missionTargetX < 0f) return
        val pulse = 52f + sin(worldTime * 6f) * 12f
        paint.color = Color.argb(95, 204, 255, 0)
        canvas.drawCircle(missionTargetX, missionTargetY, pulse, paint)
        strokePaint.color = Color.parseColor("#CCFF00")
        strokePaint.strokeWidth = 5f
        canvas.drawCircle(missionTargetX, missionTargetY, pulse, strokePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#CCFF00")
        textPaint.textSize = 20f
        textPaint.isFakeBoldText = true
        val lbl = if (missionStage == 3 && missionSubStep == 0) "GOLDEN AALOO" else "MISSION"
        canvas.drawText(lbl, missionTargetX, missionTargetY - pulse - 10f, textPaint)
    }

    private fun drawBuildings3D(canvas: Canvas, halfW: Float, halfH: Float) {
        val visible = buildings.filter { b ->
            abs(b.rect.centerX() - camX) < halfW + 460f &&
                abs(b.rect.centerY() - camY) < halfH + 460f
        }.sortedByDescending { b ->
            val dx = b.rect.centerX() - camX
            val dy = b.rect.centerY() - camY
            dx * dx + dy * dy
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        for (b in visible) {
            val z = b.height3D
            val sL = projX(b.rect.left, 0f, halfW)
            val sR = projX(b.rect.right, 0f, halfW)
            val sT = projY(b.rect.top, 0f, halfH)
            val sB = projY(b.rect.bottom, 0f, halfH)

            val rL = projX(b.rect.left, z, halfW)
            val rR = projX(b.rect.right, z, halfW)
            val rT = projY(b.rect.top, z, halfH)
            val rB = projY(b.rect.bottom, z, halfH)

            // 1. Ground Shadow
            paint.color = Color.argb(105, 0, 0, 0)
            canvas.drawRect(sL + 20f, sT + 20f, sR + 20f, sB + 20f, paint)

            // 2. Visible 3D Perspective Side Walls with Glowing Windows
            if (camY > b.rect.bottom) {
                draw3DWallQuad(canvas, sL, sB, sR, sB, rR, rB, rL, rB, b.wallColor)
            }
            if (camY < b.rect.top) {
                draw3DWallQuad(canvas, sL, sT, sR, sT, rR, rT, rL, rT, b.wallColor)
            }
            if (camX > b.rect.right) {
                draw3DWallQuad(canvas, sR, sT, sR, sB, rR, rB, rR, rT, b.sideColor)
            }
            if (camX < b.rect.left) {
                draw3DWallQuad(canvas, sL, sT, sL, sB, rL, rB, rL, rT, b.sideColor)
            }

            // 3. 3D Roof Deck
            paint.color = b.roofColor
            canvas.drawRect(rL, rT, rR, rB, paint)

            // Parapet border
            strokePaint.color = b.wallColor
            strokePaint.strokeWidth = 6f
            canvas.drawRect(rL + 3f, rT + 3f, rR - 3f, rB - 3f, strokePaint)

            // Inner Rooftop Detail
            val rcx = (rL + rR) * 0.5f
            val rcy = (rT + rB) * 0.5f
            val rw = rR - rL

            if (b.isSansad) {
                // Iconic Sansad Helipad on 3D Roof!
                paint.color = Color.parseColor("#263238")
                canvas.drawCircle(rcx, rcy, rw * 0.34f, paint)
                strokePaint.color = Color.parseColor("#CCFF00")
                strokePaint.strokeWidth = 6f
                canvas.drawCircle(rcx, rcy, rw * 0.34f, strokePaint)
                textPaint.color = Color.parseColor("#CCFF00")
                textPaint.textSize = (rw * 0.22f).coerceIn(24f, 64f)
                canvas.drawText("H", rcx, rcy + 14f, textPaint)
            } else if (b.isWashZone) {
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 5f
                canvas.drawCircle(rcx, rcy, rw * 0.28f, strokePaint)
            } else {
                paint.color = Color.argb(55, 0, 0, 0)
                canvas.drawRect(rL + rw * 0.14f, rT + rw * 0.14f, rR - rw * 0.14f, rB - rw * 0.14f, paint)
            }

            // Rooftop Neon Billboard Name
            textPaint.color = if (b.isWashZone || b.isSansad) Color.parseColor("#09090B") else Color.WHITE
            textPaint.textSize = (rw * 0.085f).coerceIn(16f, 30f)
            canvas.drawText(b.label, rcx, rT + rw * 0.22f, textPaint)

            textPaint.color = Color.parseColor("#CCFF00")
            textPaint.textSize = (rw * 0.055f).coerceIn(12f, 20f)
            canvas.drawText(b.subSign, rcx, rB - rw * 0.12f, textPaint)
        }
    }

    private fun draw3DWallQuad(
        canvas: Canvas,
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float,
        color: Int
    ) {
        wallPath.reset()
        wallPath.moveTo(x1, y1)
        wallPath.lineTo(x2, y2)
        wallPath.lineTo(x3, y3)
        wallPath.lineTo(x4, y4)
        wallPath.close()

        paint.color = color
        canvas.drawPath(wallPath, paint)

        // Subtle glowing 3D window stripes along wall
        strokePaint.color = Color.argb(135, 255, 241, 168)
        strokePaint.strokeWidth = 4f
        for (f in listOf(0.32f, 0.58f, 0.80f)) {
            val lx = x1 + (x4 - x1) * f
            val ly = y1 + (y4 - y1) * f
            val rx = x2 + (x3 - x2) * f
            val ry = y2 + (y3 - y2) * f
            canvas.drawLine(
                lx + (rx - lx) * 0.12f, ly + (ry - ly) * 0.12f,
                lx + (rx - lx) * 0.88f, ly + (ry - ly) * 0.88f,
                strokePaint
            )
        }

        strokePaint.color = Color.argb(120, 0, 0, 0)
        strokePaint.strokeWidth = 2.5f
        canvas.drawPath(wallPath, strokePaint)
    }

    private fun drawPollingBooths(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        for (booth in booths) {
            val ready = booth.cooldown <= 0f
            if (ready) {
                val pulse = 44f + sin(worldTime * 5f) * 7f
                paint.color = Color.argb(80, 255, 214, 0)
                canvas.drawCircle(booth.x, booth.y, pulse, paint)
            }
            paint.color = if (ready) Color.parseColor("#FFAB00") else Color.parseColor("#616161")
            canvas.drawRoundRect(RectF(booth.x - 26f, booth.y - 22f, booth.x + 26f, booth.y + 22f), 8f, 8f, paint)
            paint.color = Color.parseColor("#111111")
            canvas.drawRect(booth.x - 14f, booth.y - 8f, booth.x + 14f, booth.y + 4f, paint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 15f
            val sub = if (ready) "EVM +${booth.votesReady}V" else "EMPTY (${booth.cooldown.toInt()}s)"
            canvas.drawText(sub, booth.x, booth.y - 30f, textPaint)

            if (booth.stealProgress > 0f) {
                strokePaint.color = Color.parseColor("#CCFF00")
                strokePaint.strokeWidth = 8f
                canvas.drawArc(
                    RectF(booth.x - 42f, booth.y - 42f, booth.x + 42f, booth.y + 42f),
                    -90f, 360f * booth.stealProgress, false, strokePaint
                )
            }
        }
    }

    private fun drawVotePickups(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 15f
        textPaint.isFakeBoldText = true
        for (p in pickups) {
            if (!p.active) continue
            paint.color = Color.parseColor("#00E676")
            canvas.drawCircle(p.x, p.y, 16f, paint)
            textPaint.color = Color.parseColor("#09090B")
            canvas.drawText("V", p.x, p.y + 6f, textPaint)
        }
    }

    private fun drawCitizens(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 14f
        for (npc in citizens) {
            if (abs(npc.x - camX) > width * 0.7f || abs(npc.y - camY) > height * 0.7f) continue
            paint.color = Color.argb(90, 0, 0, 0)
            canvas.drawCircle(npc.x + 4f, npc.y + 4f, 11f, paint)
            paint.color = npc.shirtColor
            canvas.drawCircle(npc.x, npc.y, 11f, paint)
            paint.color = Color.parseColor("#3E2723")
            canvas.drawCircle(npc.x, npc.y, 6f, paint)

            if (npc.chatTimer > 0f) {
                paint.color = Color.argb(200, 255, 255, 255)
                canvas.drawRoundRect(RectF(npc.x - 48f, npc.y - 38f, npc.x + 48f, npc.y - 16f), 10f, 10f, paint)
                textPaint.color = Color.BLACK
                canvas.drawText(npc.chatText, npc.x, npc.y - 22f, textPaint)
            }
        }
    }

    private fun drawTraffic(canvas: Canvas) {
        for (car in traffic) {
            if (abs(car.x - camX) > width * 0.75f || abs(car.y - camY) > height * 0.75f) continue
            canvas.save()
            canvas.translate(car.x, car.y)
            canvas.rotate(Math.toDegrees(atan2(car.vy.toDouble(), car.vx.toDouble())).toFloat())
            paint.color = Color.argb(90, 0, 0, 0)
            canvas.drawRoundRect(RectF(-28f, -15f, 34f, 21f), 8f, 8f, paint)
            paint.color = car.color
            canvas.drawRoundRect(RectF(-30f, -17f, 30f, 17f), 8f, 8f, paint)
            paint.color = Color.parseColor("#263238")
            canvas.drawRect(8f, -13f, 20f, 13f, paint)
            canvas.restore()
        }
    }

    private fun drawAllVehicles3D(canvas: Canvas, halfW: Float, halfH: Float, flyingOnly: Boolean) {
        for (v in vehicles) {
            val isThisFlying = (v === currentVehicle && v.type.canFly && chopperAltitude > 10f)
            if (flyingOnly != isThisFlying) continue
            if (abs(v.x - camX) > halfW + 250f || abs(v.y - camY) > halfH + 250f) continue

            val baseZ = if (isThisFlying) chopperAltitude else 0f
            val deg = Math.toDegrees(v.angle.toDouble()).toFloat()
            val hw = v.type.w * 0.5f
            val hh = v.type.h * 0.5f

            // Ground shadow
            val shX = projX(v.x + 12f + baseZ * 0.25f, 0f, halfW)
            val shY = projY(v.y + 12f + baseZ * 0.25f, 0f, halfH)
            canvas.save()
            canvas.translate(shX, shY)
            canvas.rotate(deg)
            paint.color = Color.argb(if (isThisFlying) 65 else 100, 0, 0, 0)
            canvas.drawRoundRect(RectF(-hw, -hh, hw, hh), 12f, 12f, paint)
            canvas.restore()

            // Stacked 3D Slices for chunky 3D vehicle height!
            val layers = 4
            for (step in 0..layers) {
                val frac = step.toFloat() / layers
                val lz = baseZ + frac * v.type.height3D
                val sx = projX(v.x, lz, halfW)
                val sy = projY(v.y, lz, halfH)
                val scale = camAlt / (camAlt - lz).coerceAtLeast(140f)

                canvas.save()
                canvas.translate(sx, sy)
                canvas.rotate(deg)
                canvas.scale(scale, scale)

                if (step < layers) {
                    paint.color = if (step == 0) Color.parseColor("#1A1A1E") else v.type.bodyColor
                    canvas.drawRoundRect(RectF(-hw, -hh, hw, hh), 10f, 10f, paint)
                } else {
                    // Top Roof Slice
                    paint.color = v.type.accentColor
                    canvas.drawRoundRect(RectF(-hw * 0.62f, -hh * 0.76f, hw * 0.42f, hh * 0.76f), 8f, 8f, paint)

                    // Windshield
                    paint.color = Color.parseColor("#263238")
                    canvas.drawRect(hw * 0.42f, -hh * 0.72f, hw * 0.74f, hh * 0.72f, paint)

                    // Bulldozer Front Steel Blade
                    if (v.type.isTank) {
                        paint.color = Color.parseColor("#CFD8DC")
                        canvas.drawRect(hw * 0.85f, -hh * 1.12f, hw * 1.12f, hh * 1.12f, paint)
                    }

                    // VIP Beacon on Netaji Fortuner
                    if (v.type == VehicleType.NETAJI_SUV) {
                        paint.color = if (((worldTime * 9f).toInt() % 2) == 0) Color.RED else Color.BLUE
                        canvas.drawCircle(0f, 0f, 7f, paint)
                    }

                    // Spinning 3D Rotor Blades on VIP Chopper
                    if (v.type.canFly) {
                        canvas.rotate(worldTime * (if (isThisFlying) 900f else 180f))
                        paint.color = Color.argb(200, 236, 239, 241)
                        canvas.drawRect(-72f, -4f, 72f, 4f, paint)
                        canvas.drawRect(-4f, -72f, 4f, 72f, paint)
                    }
                }
                canvas.restore()
            }
        }
    }

    private fun drawPlayer3D(canvas: Canvas, halfW: Float, halfH: Float) {
        val deg = Math.toDegrees(playerAngle.toDouble()).toFloat()

        // Shadow
        val shX = projX(playerX + 7f, 0f, halfW)
        val shY = projY(playerY + 7f, 0f, halfH)
        paint.color = Color.argb(105, 0, 0, 0)
        canvas.drawCircle(shX, shY, 17f, paint)

        // Torso Layer (z = 16)
        val tx = projX(playerX, 16f, halfW)
        val ty = projY(playerY, 16f, halfH)
        canvas.save()
        canvas.translate(tx, ty)
        canvas.rotate(deg)
        val swing = sin(walkPhase) * 6f
        paint.color = Color.parseColor("#4E342E")
        canvas.drawCircle(swing, -11f, 6f, paint)
        canvas.drawCircle(-swing, 11f, 6f, paint)
        // Saffron Jacket + White Kurta
        paint.color = Color.parseColor("#FF6F00")
        canvas.drawRoundRect(RectF(-13f, -21f, 13f, 21f), 11f, 11f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(4f, 0f, 11f, paint)
        canvas.restore()

        // Head Layer (z = 34 -> true 3D height!)
        val hx = projX(playerX, 34f, halfW)
        val hy = projY(playerY, 34f, halfH)
        canvas.save()
        canvas.translate(hx, hy)
        canvas.rotate(deg)
        paint.color = Color.parseColor("#E0AC69")
        canvas.drawCircle(2f, 0f, 10.5f, paint)
        paint.color = Color.parseColor("#ECEFF1")
        canvas.drawArc(RectF(-10f, -10.5f, 11f, 10.5f), 110f, 140f, true, paint)
        canvas.drawCircle(11.5f, 0f, 5f, paint)
        canvas.restore()

        // Floating Meme Speech Bubble above Player
        if (playerBubbleTimer > 0f && playerBubble.isNotEmpty()) {
            drawSpeechBubble(canvas, hx, hy - 34f, playerBubble, Color.parseColor("#FF6F00"), Color.WHITE)
        }
    }

    private fun drawPatrols3D(canvas: Canvas, halfW: Float, halfH: Float) {
        if (wantedStars <= 0) return
        val activeChasers = wantedStars.coerceAtMost(patrols.size)

        for (i in 0 until activeChasers) {
            val cop = patrols[i]
            val deg = Math.toDegrees(cop.angle.toDouble()).toFloat()

            // 3D Stacked Police Jeep
            for (step in 0..3) {
                val lz = step * 10f
                val sx = projX(cop.x, lz, halfW)
                val sy = projY(cop.y, lz, halfH)
                val scale = camAlt / (camAlt - lz).coerceAtLeast(140f)

                canvas.save()
                canvas.translate(sx, sy)
                canvas.rotate(deg)
                canvas.scale(scale, scale)

                if (step < 3) {
                    paint.color = if (cop.isRahulChief) Color.parseColor("#0277BD") else Color.parseColor("#1A237E")
                    canvas.drawRoundRect(RectF(-40f, -22f, 40f, 22f), 9f, 9f, paint)
                } else {
                    paint.color = Color.WHITE
                    canvas.drawRoundRect(RectF(-20f, -18f, 18f, 18f), 6f, 6f, paint)

                    // Flashing Red/Blue Siren Bar
                    val flashRed = ((worldTime * 11f).toInt() + i) % 2 == 0
                    paint.color = if (flashRed) Color.RED else Color.parseColor("#2979FF")
                    canvas.drawCircle(2f, -7f, 6.5f, paint)
                    paint.color = if (flashRed) Color.parseColor("#2979FF") else Color.RED
                    canvas.drawCircle(2f, 7f, 6.5f, paint)
                }
                canvas.restore()
            }

            // Label + Viral Speech Bubble above Police Chief RAHUL G!
            val topX = projX(cop.x, 42f, halfW)
            val topY = projY(cop.y, 42f, halfH)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.isFakeBoldText = true
            textPaint.textSize = 16f
            textPaint.color = if (cop.isRahulChief) Color.parseColor("#40C4FF") else Color.parseColor("#FF8A80")
            canvas.drawText(if (cop.isRahulChief) "★ POLICE CHIEF RAHUL G ★" else "EC SQUAD", topX, topY - 26f, textPaint)

            if (cop.isRahulChief && cop.bubbleTimer > 0f && cop.bubbleText.isNotEmpty()) {
                drawSpeechBubble(canvas, topX, topY - 58f, cop.bubbleText, Color.parseColor("#1565C0"), Color.WHITE)
            }
        }
    }

    private fun drawSpeechBubble(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        text: String,
        bgCol: Int,
        txtCol: Int
    ) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 18f
        textPaint.isFakeBoldText = true
        val tw = textPaint.measureText(text) + 28f
        paint.color = bgCol
        canvas.drawRoundRect(RectF(cx - tw * 0.5f, cy - 22f, cx + tw * 0.5f, cy + 12f), 14f, 14f, paint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(RectF(cx - tw * 0.5f, cy - 22f, cx + tw * 0.5f, cy + 12f), 14f, 14f, strokePaint)
        textPaint.color = txtCol
        canvas.drawText(text, cx, cy + 1f, textPaint)
    }

    private fun drawGpsArrow(canvas: Canvas, halfW: Float, halfH: Float) {
        val dx = missionTargetX - playerX
        val dy = missionTargetY - playerY
        val dist = hypot(dx, dy)
        if (dist < 150f) return
        val angle = atan2(dy, dx)
        val ax = halfW + cos(angle) * 86f
        val ay = halfH + sin(angle) * 86f

        canvas.save()
        canvas.translate(ax, ay)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        val tri = Path().apply {
            moveTo(20f, 0f)
            lineTo(-10f, -12f)
            lineTo(-4f, 0f)
            lineTo(-10f, 12f)
            close()
        }
        paint.color = Color.parseColor("#CCFF00")
        canvas.drawPath(tri, paint)
        canvas.restore()
    }

    // ==================== HUD, DIALOGUE & TOUCH CONTROLS ====================

    private fun drawHUD(canvas: Canvas, w: Float, h: Float) {
        // Top-Left Stats Glass Card
        paint.color = Color.argb(200, 10, 10, 16)
        canvas.drawRoundRect(RectF(20f, 18f, 415f, 166f), 20f, 20f, paint)
        strokePaint.color = Color.argb(60, 255, 255, 255)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(RectF(20f, 18f, 415f, 166f), 20f, 20f, strokePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true

        textPaint.color = Color.parseColor("#FFAB00")
        textPaint.textSize = 18f
        canvas.drawText("MTA 6 • 3D INDIAN OPEN WORLD", 38f, 44f, textPaint)

        textPaint.color = Color.parseColor("#00E676")
        textPaint.textSize = 28f
        canvas.drawText("VOTES: $votes", 38f, 76f, textPaint)

        // Health Bar
        paint.color = Color.parseColor("#2E2E38")
        canvas.drawRoundRect(RectF(38f, 88f, 210f, 104f), 8f, 8f, paint)
        paint.color = if (playerHealth > 35f) Color.parseColor("#CCFF00") else Color.parseColor("#FF1744")
        canvas.drawRoundRect(RectF(38f, 88f, 38f + 172f * (playerHealth / 100f), 104f), 8f, 8f, paint)

        // Wanted Stars
        textPaint.textSize = 23f
        val starStr = "★".repeat(wantedStars) + "☆".repeat(5 - wantedStars)
        textPaint.color = if (wantedStars > 0) Color.parseColor("#FF3D00") else Color.parseColor("#757575")
        canvas.drawText(starStr, 222f, 105f, textPaint)

        // Active GTA-6 Story Mission Line
        textPaint.color = Color.parseColor("#CCFF00")
        textPaint.textSize = 16f
        canvas.drawText(missionTitle, 38f, 130f, textPaint)
        textPaint.color = Color.parseColor("#E0E0E0")
        textPaint.textSize = 14f
        canvas.drawText(missionGoalText.take(48), 38f, 152f, textPaint)

        // Top Center Alert Banner
        if (bannerTimer > 0f && bannerText.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 22f
            val tw = textPaint.measureText(bannerText) + 46f
            paint.color = Color.argb(220, 12, 12, 18)
            canvas.drawRoundRect(RectF(w * 0.5f - tw * 0.5f, 22f, w * 0.5f + tw * 0.5f, 68f), 22f, 22f, paint)
            textPaint.color = bannerColor
            canvas.drawText(bannerText, w * 0.5f, 52f, textPaint)
        }
    }

    private fun drawDialogueOverlay(canvas: Canvas, w: Float, h: Float) {
        val dlg = activeDialogue ?: return
        val bw = (w * 0.56f).coerceAtMost(660f)
        val left = w * 0.5f - bw * 0.5f
        val top = h - 135f

        paint.color = Color.argb(232, 10, 10, 16)
        canvas.drawRoundRect(RectF(left, top, left + bw, top + 110f), 20f, 20f, paint)
        strokePaint.color = dlg.badgeColor
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(RectF(left, top, left + bw, top + 110f), 20f, 20f, strokePaint)

        // Speaker Pill
        paint.color = dlg.badgeColor
        canvas.drawRoundRect(RectF(left + 16f, top + 12f, left + 270f, top + 40f), 12f, 12f, paint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.color = Color.WHITE
        textPaint.textSize = 16f
        canvas.drawText(dlg.speaker, left + 28f, top + 32f, textPaint)

        // Dialogue Text (Auto 2-line wrap)
        textPaint.textSize = 19f
        textPaint.color = Color.parseColor("#F5F5F5")
        val words = dlg.line.split(" ")
        val line1 = StringBuilder()
        val line2 = StringBuilder()
        for (word in words) {
            if (textPaint.measureText("$line1 $word") < bw - 36f) {
                if (line1.isNotEmpty()) line1.append(" ")
                line1.append(word)
            } else {
                if (line2.isNotEmpty()) line2.append(" ")
                line2.append(word)
            }
        }
        canvas.drawText(line1.toString(), left + 18f, top + 70f, textPaint)
        if (line2.isNotEmpty()) {
            canvas.drawText(line2.toString(), left + 18f, top + 96f, textPaint)
        }
    }

    private fun drawMiniMap(canvas: Canvas, w: Float) {
        val mapSize = 152f
        val mx = w - mapSize - 22f
        val my = 18f

        paint.color = Color.argb(205, 12, 15, 20)
        canvas.drawRoundRect(RectF(mx, my, mx + mapSize, my + mapSize), 16f, 16f, paint)
        strokePaint.color = Color.parseColor("#CCFF00")
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(RectF(mx, my, mx + mapSize, my + mapSize), 16f, 16f, strokePaint)

        val sx = mapSize / worldW
        val sy = mapSize / worldH

        // Washing Zones (Cyan)
        paint.color = Color.parseColor("#00E5FF")
        for (b in buildings) {
            if (b.isWashZone) {
                canvas.drawCircle(mx + b.rect.centerX() * sx, my + b.rect.centerY() * sy, 5f, paint)
            }
        }

        // Booths (Gold)
        paint.color = Color.parseColor("#FFD600")
        for (booth in booths) {
            if (booth.cooldown <= 0f) {
                canvas.drawCircle(mx + booth.x * sx, my + booth.y * sy, 3.5f, paint)
            }
        }

        // Mission Target (Flashing Lime Ring)
        if (missionTargetX > 0f) {
            strokePaint.color = Color.parseColor("#CCFF00")
            strokePaint.strokeWidth = 3f
            canvas.drawCircle(mx + missionTargetX * sx, my + missionTargetY * sy, 7f, strokePaint)
        }

        // Police Chief Rahul G (Blue/Red flashing) & Cops
        if (wantedStars > 0) {
            for (i in 0 until wantedStars.coerceAtMost(patrols.size)) {
                val cop = patrols[i]
                paint.color = if (cop.isRahulChief && ((worldTime * 8f).toInt() % 2 == 0)) Color.parseColor("#2979FF") else Color.RED
                canvas.drawCircle(mx + cop.x * sx, my + cop.y * sy, if (cop.isRahulChief) 5.5f else 4f, paint)
            }
        }

        // Player
        paint.color = Color.WHITE
        canvas.drawCircle(mx + playerX * sx, my + playerY * sy, 5f, paint)
    }

    private fun drawTouchControls(canvas: Canvas) {
        // Floating Left Joystick
        paint.color = Color.argb(85, 255, 255, 255)
        canvas.drawCircle(joyOriginX, joyOriginY, joyRadius, paint)
        strokePaint.color = Color.argb(150, 204, 255, 0)
        strokePaint.strokeWidth = 3f
        canvas.drawCircle(joyOriginX, joyOriginY, joyRadius, strokePaint)

        val knobX = joyOriginX + joyDx * (joyRadius * 0.65f)
        val knobY = joyOriginY + joyDy * (joyRadius * 0.65f)
        paint.color = Color.parseColor("#CCFF00")
        canvas.drawCircle(knobX, knobY, joyRadius * 0.36f, paint)

        // Right Action Buttons
        drawActionButton(canvas, btnStealX, btnStealY, btnRadius, if (stealHeld) "#FF3D00" else "#FFAB00", "CHORI")
        drawActionButton(canvas, btnMemeX, btnMemeY, btnRadius * 0.78f, "#E040FB", "MEME")
        drawActionButton(
            canvas, btnRideX, btnRideY, btnRadius * 0.86f,
            if (currentVehicle != null) "#00E676" else "#2979FF",
            if (currentVehicle != null) "EXIT" else "RIDE"
        )
        drawActionButton(canvas, btnNitroX, btnNitroY, btnRadius * 0.86f, if (nitroHeld) "#CCFF00" else "#42424A", "NITRO")
    }

    private fun drawActionButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        hex: String,
        label: String
    ) {
        paint.color = Color.parseColor(hex)
        paint.alpha = 220
        canvas.drawCircle(cx, cy, r, paint)
        paint.alpha = 255
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, r, strokePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#09090B")
        textPaint.textSize = 19f
        textPaint.isFakeBoldText = true
        canvas.drawText(label, cx, cy + 7f, textPaint)
    }

    private fun drawTitleOverlay(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.argb(225, 10, 8, 18)
        canvas.drawRect(0f, 0f, w, h, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        textPaint.color = Color.parseColor("#FF6D00")
        textPaint.textSize = 78f
        canvas.drawText("MTA 6", w * 0.5f, h * 0.28f, textPaint)

        textPaint.color = Color.parseColor("#CCFF00")
        textPaint.textSize = 32f
        canvas.drawText("MODI THEFT AUTO • 3D STORY EDITION", w * 0.5f, h * 0.39f, textPaint)

        textPaint.color = Color.parseColor("#E0E0E0")
        textPaint.textSize = 21f
        canvas.drawText("5 GTA-Style Story Missions • 3D City • Chopper & Bulldozer • Police Chief Rahul G!", w * 0.5f, h * 0.52f, textPaint)

        paint.color = Color.parseColor("#CCFF00")
        canvas.drawRoundRect(RectF(w * 0.5f - 180f, h * 0.64f, w * 0.5f + 180f, h * 0.64f + 78f), 39f, 39f, paint)
        textPaint.color = Color.parseColor("#09090B")
        textPaint.textSize = 30f
        canvas.drawText("TAP TO PLAY 3D", w * 0.5f, h * 0.64f + 50f, textPaint)
    }

    private fun drawEndOverlay(canvas: Canvas, w: Float, h: Float, victory: Boolean) {
        paint.color = if (victory) Color.argb(225, 8, 22, 16) else Color.argb(225, 26, 6, 12)
        canvas.drawRect(0f, 0f, w, h, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.color = if (victory) Color.parseColor("#CCFF00") else Color.parseColor("#FF1744")
        textPaint.textSize = 62f
        canvas.drawText(if (victory) "ELECTION JEET GAYE!" else "BUSTED BY RAHUL G!", w * 0.5f, h * 0.36f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 26f
        canvas.drawText("Total Votes: $votes  •  Booths Looted: $totalStolenBooths", w * 0.5f, h * 0.49f, textPaint)

        paint.color = Color.parseColor("#FFAB00")
        canvas.drawRoundRect(RectF(w * 0.5f - 210f, h * 0.62f, w * 0.5f + 210f, h * 0.62f + 74f), 37f, 37f, paint)
        textPaint.color = Color.parseColor("#09090B")
        textPaint.textSize = 25f
        canvas.drawText(if (victory) "TAP FOR FREE ROAM" else "TAP TO RESPAWN", w * 0.5f, h * 0.62f + 46f, textPaint)
    }

    // ==================== SMOOTH MULTI-TOUCH INPUT ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        if (state == GameState.TITLE && action == MotionEvent.ACTION_UP) {
            startNewSession(keepProgress = false)
            return true
        }
        if (state == GameState.BUSTED && action == MotionEvent.ACTION_UP) {
            startNewSession(keepProgress = true)
            return true
        }
        if (state == GameState.VICTORY && action == MotionEvent.ACTION_UP) {
            state = GameState.PLAYING
            setupCurrentMission()
            return true
        }

        val actionIdx = event.actionIndex
        val pointerId = event.getPointerId(actionIdx)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIdx)
                val y = event.getY(actionIdx)

                // Dismiss story dialogue if tapped in middle
                if (activeDialogue != null && x in (width * 0.25f)..(width * 0.75f) && y > height - 150f) {
                    activeDialogue = null
                }

                if (x < width * 0.48f && joyPointerId == -1) {
                    joyPointerId = pointerId
                    // Floating joystick snaps to thumb smoothly!
                    joyOriginX = x.coerceIn(joyRadius + 15f, width * 0.42f)
                    joyOriginY = y.coerceIn(height * 0.40f, height - joyRadius - 15f)
                    updateJoystick(x, y)
                } else {
                    if (hypot(x - btnRideX, y - btnRideY) <= btnRadius * 1.15f) rideRequested = true
                    if (hypot(x - btnMemeX, y - btnMemeY) <= btnRadius * 1.15f) memeRequested = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId == joyPointerId) {
                    joyPointerId = -1
                    joyDx = 0f
                    joyDy = 0f
                    joyOriginX = defaultJoyX
                    joyOriginY = defaultJoyY
                }
            }
        }

        var stealDown = false
        var nitroDown = false
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val isLeaving = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == actionIdx
            if (isLeaving) continue

            val x = event.getX(i)
            val y = event.getY(i)
            if (id == joyPointerId) {
                updateJoystick(x, y)
            } else {
                if (hypot(x - btnStealX, y - btnStealY) <= btnRadius * 1.25f) stealDown = true
                if (hypot(x - btnNitroX, y - btnNitroY) <= btnRadius * 1.25f) nitroDown = true
            }
        }
        stealHeld = stealDown
        nitroHeld = nitroDown
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        val dx = x - joyOriginX
        val dy = y - joyOriginY
        val dist = hypot(dx, dy)
        if (dist > 0f) {
            val clamped = dist.coerceAtMost(joyRadius)
            joyDx = (dx / dist) * (clamped / joyRadius)
            joyDy = (dy / dist) * (clamped / joyRadius)
        }
    }
}
