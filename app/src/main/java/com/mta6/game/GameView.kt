package com.mta6.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), Runnable {

    private enum class GameState { TITLE, PLAYING, BUSTED }
    private enum class VehicleType(
        val title: String,
        val speed: Float,
        val w: Float,
        val h: Float,
        val bodyColor: Int,
        val accentColor: Int
    ) {
        NETAJI_SUV("NETAJI SUV", 490f, 84f, 46f, Color.parseColor("#F5F5F5"), Color.parseColor("#FF6D00")),
        AUTO("AUTO RICKSHAW", 390f, 64f, 38f, Color.parseColor("#FFD600"), Color.parseColor("#00C853")),
        BULLET("BULLET 350", 540f, 54f, 24f, Color.parseColor("#D50000"), Color.parseColor("#212121"))
    }

    private data class Building(
        val rect: RectF,
        val label: String,
        val color: Int,
        val roofColor: Int,
        val isWashZone: Boolean = false
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
        var hitCooldown: Float = 0f
    )

    // World Constants (Indian Open-World Grid)
    private val worldW = 3000f
    private val worldH = 3000f
    private val blockStep = 600f
    private val roadWidth = 170f

    // Loop State
    @Volatile private var running = false
    private var gameThread: Thread? = null
    @Volatile private var state = GameState.TITLE

    // Player & Economy (Money = Votes!)
    private var playerX = 1500f
    private var playerY = 1500f
    private var playerAngle = 0f
    private var walkPhase = 0f
    private var playerHealth = 100f
    private var votes = 0
    private var totalStolenBooths = 0
    private var wantedStars = 0
    private var wantedDecayTimer = 0f
    private var currentVehicle: Vehicle? = null
    private var washCooldown = 0f

    // Camera
    private var camX = 1500f
    private var camY = 1500f
    private var bannerText = ""
    private var bannerColor = Color.parseColor("#FFAB00")
    private var bannerTimer = 0f
    private var sirenTime = 0f

    // World Entities
    private val buildings = mutableListOf<Building>()
    private val booths = mutableListOf<PollingBooth>()
    private val pickups = mutableListOf<VotePickup>()
    private val vehicles = mutableListOf<Vehicle>()
    private val patrols = mutableListOf<PatrolUnit>()

    // Multi-Touch Controls
    @Volatile private var joyPointerId = -1
    @Volatile private var joyDx = 0f
    @Volatile private var joyDy = 0f
    @Volatile private var stealHeld = false
    @Volatile private var nitroHeld = false
    @Volatile private var rideRequested = false

    private var joyBaseX = 180f
    private var joyBaseY = 520f
    private var joyRadius = 115f

    private var btnStealX = 1000f
    private var btnStealY = 540f
    private var btnRideX = 1150f
    private var btnRideY = 430f
    private var btnNitroX = 1160f
    private var btnNitroY = 590f
    private val btnRadius = 68f

    // Reusable Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        buildIndianOpenWorld()
    }

    private fun buildIndianOpenWorld() {
        buildings.clear()
        booths.clear()
        pickups.clear()
        vehicles.clear()
        patrols.clear()

        val landmarkNames = listOf(
            "SANSAD BHAVAN", "GUJARAT PLAZA", "PINK BAZAAR", "CHAI TAPRI",
            "WASHING ZONE", "MARINE TOWERS", "RALLY GROUND", "DILLI HAAT",
            "UP EXPRESS HUB", "MUMBAI FILM CITY", "JAIPUR FORT", "LAL CHOWK",
            "KISAN MANDI", "METRO DEPOT", "IT CELL HQ", "INDIA GATE PARK",
            "SAMOSA JUNCTION", "MEDIA STUDIO", "VIP BUNGLOW", "OLD BAZAAR",
            "DHABA 24x7", "STADIUM", "TECH PARK", "CRICKET CHOWK", "BUS ADDA"
        )

        val blockColors = listOf(
            Color.parseColor("#D87A56"),
            Color.parseColor("#E89B72"),
            Color.parseColor("#C76D7E"),
            Color.parseColor("#5D737E"),
            Color.parseColor("#6E8898"),
            Color.parseColor("#B5835A")
        )

        var idx = 0
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                val bx = col * blockStep + roadWidth * 0.5f + 35f
                val by = row * blockStep + roadWidth * 0.5f + 35f
                val bw = blockStep - roadWidth - 70f
                val bh = blockStep - roadWidth - 70f

                val name = landmarkNames[idx % landmarkNames.size]
                val isWash = (row == 0 && col == 4) || (row == 4 && col == 0)
                val finalName = if (isWash) "WASHING ZONE" else name
                val baseCol = if (isWash) Color.parseColor("#00ACC1") else blockColors[idx % blockColors.size]
                val roofCol = if (isWash) Color.parseColor("#26C6DA") else Color.parseColor("#37474F")

                buildings.add(
                    Building(
                        rect = RectF(bx, by, bx + bw, by + bh),
                        label = finalName,
                        color = baseCol,
                        roofColor = roofCol,
                        isWashZone = isWash
                    )
                )
                idx++
            }
        }

        // 10 Glowing Polling Booths (EVM Vote Chori Points)
        val boothSpots = listOf(
            Triple(600f, 600f, "BOOTH #1 DILLI"),
            Triple(1200f, 600f, "BOOTH #2 GUJARAT"),
            Triple(1800f, 600f, "BOOTH #3 JAIPUR"),
            Triple(2400f, 1200f, "BOOTH #4 UP"),
            Triple(600f, 1200f, "BOOTH #5 MUMBAI"),
            Triple(1800f, 1200f, "BOOTH #6 BIHAR"),
            Triple(1200f, 1800f, "BOOTH #7 PUNJAB"),
            Triple(2400f, 1800f, "BOOTH #8 BENGAL"),
            Triple(600f, 2400f, "BOOTH #9 SOUTH"),
            Triple(1800f, 2400f, "BOOTH #10 MP")
        )
        for ((bx, by, bName) in boothSpots) {
            booths.add(PollingBooth(bx + 75f, by - 75f, bName, votesReady = 1000))
        }

        // Loose Vote Bundles across Indian roads
        for (i in 0 until 22) {
            val onHoriz = i % 2 == 0
            val px = if (onHoriz) Random.nextFloat() * (worldW - 200f) + 100f else (Random.nextInt(1, 5) * blockStep)
            val py = if (onHoriz) (Random.nextInt(1, 5) * blockStep) else Random.nextFloat() * (worldH - 200f) + 100f
            pickups.add(VotePickup(px, py, value = 250))
        }

        // Indian Vehicles parked on intersections
        val rides = listOf(
            Triple(1500f, 1410f, VehicleType.NETAJI_SUV),
            Triple(1380f, 1500f, VehicleType.AUTO),
            Triple(1620f, 1500f, VehicleType.BULLET),
            Triple(600f, 1100f, VehicleType.NETAJI_SUV),
            Triple(1200f, 520f, VehicleType.AUTO),
            Triple(1800f, 1750f, VehicleType.BULLET),
            Triple(2400f, 650f, VehicleType.NETAJI_SUV),
            Triple(600f, 2300f, VehicleType.AUTO),
            Triple(2400f, 2320f, VehicleType.BULLET),
            Triple(1200f, 2400f, VehicleType.NETAJI_SUV)
        )
        for ((vx, vy, vType) in rides) {
            vehicles.add(Vehicle(vx, vy, angle = 0f, type = vType))
        }

        // EC / Police Patrol Units
        patrols.add(PatrolUnit(600f, 600f, 0f, 270f))
        patrols.add(PatrolUnit(2400f, 600f, 0f, 295f))
        patrols.add(PatrolUnit(600f, 2400f, 0f, 315f))
        patrols.add(PatrolUnit(2400f, 2400f, 0f, 340f))
    }

    private fun startNewSession(keepVotes: Boolean) {
        if (!keepVotes) {
            votes = 0
            totalStolenBooths = 0
            buildIndianOpenWorld()
        } else {
            votes = (votes * 0.8f).toInt()
        }
        playerX = 1500f
        playerY = 1500f
        playerAngle = 0f
        playerHealth = 100f
        wantedStars = 0
        wantedDecayTimer = 0f
        currentVehicle = null
        camX = playerX
        camY = playerY
        state = GameState.PLAYING
        showBanner("MISSION: COLLECT VOTES ON INDIAN MAP!", "#FFAB00", 3.0f)
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
        try {
            gameThread?.join(500)
        } catch (_: InterruptedException) {
        }
        gameThread = null
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
        joyRadius = (h * 0.145f).coerceIn(85f, 130f)
        joyBaseX = joyRadius + 55f
        joyBaseY = h - joyRadius - 45f

        btnStealX = w - 245f
        btnStealY = h - 120f
        btnRideX = w - 105f
        btnRideY = h - 220f
        btnNitroX = w - 105f
        btnNitroY = h - 95f
    }

    // ==================== GAME LOGIC ====================

    private fun updateGame(dt: Float) {
        sirenTime += dt
        if (bannerTimer > 0f) bannerTimer -= dt
        if (washCooldown > 0f) washCooldown -= dt

        // 1. Handle Enter / Exit Vehicle
        if (rideRequested) {
            rideRequested = false
            if (currentVehicle != null) {
                currentVehicle = null
                showBanner("ON FOOT MODE", "#CCFF00", 1.5f)
            } else {
                val nearest = vehicles.minByOrNull { hypot(it.x - playerX, it.y - playerY) }
                if (nearest != null && hypot(nearest.x - playerX, nearest.y - playerY) < 115f) {
                    currentVehicle = nearest
                    showBanner("${nearest.type.title} JACKED!", "#FFAB00", 2.0f)
                } else {
                    showBanner("Koi gaadi paas nahi hai!", "#FF5252", 1.5f)
                }
            }
        }

        // 2. Player or Vehicle Movement
        val mag = hypot(joyDx, joyDy)
        if (mag > 0.08f) {
            val baseSpeed = currentVehicle?.type?.speed ?: 245f
            val boost = if (nitroHeld) 1.52f else 1.0f
            val moveSpeed = baseSpeed * boost * mag.coerceAtMost(1f)
            val vx = (joyDx / mag) * moveSpeed * dt
            val vy = (joyDy / mag) * moveSpeed * dt
            playerAngle = atan2(joyDy, joyDx)
            walkPhase += dt * (if (nitroHeld) 14f else 9f)

            val hitRadius = if (currentVehicle != null) 28f else 18f
            moveWithCollision(vx, vy, hitRadius)

            currentVehicle?.let { v ->
                v.x = playerX
                v.y = playerY
                v.angle = playerAngle
            }
        }

        // Smooth GTA Camera Follow
        camX += (playerX - camX) * (dt * 7.5f).coerceAtMost(1f)
        camY += (playerY - camY) * (dt * 7.5f).coerceAtMost(1f)

        // 3. Collect Loose Vote Bundles
        for (p in pickups) {
            if (p.active && hypot(p.x - playerX, p.y - playerY) < 48f) {
                p.active = false
                votes += p.value
                showBanner("+${p.value} VOTES COLLECTED!", "#00E676", 1.4f)
            }
        }
        if (pickups.count { it.active } < 6) {
            for (p in pickups) {
                if (!p.active) {
                    p.x = (Random.nextInt(1, 5) * blockStep) + Random.nextFloat() * 60f - 30f
                    p.y = Random.nextFloat() * (worldH - 240f) + 120f
                    p.active = true
                }
            }
        }

        // 4. Polling Booth Vote Chori System
        for (booth in booths) {
            if (booth.cooldown > 0f) {
                booth.cooldown -= dt
                if (booth.cooldown <= 0f) booth.votesReady = 1000 + Random.nextInt(0, 6) * 100
                continue
            }
            val dist = hypot(booth.x - playerX, booth.y - playerY)
            if (dist < 105f && stealHeld) {
                booth.stealProgress += dt * 0.85f
                if (booth.stealProgress >= 1f) {
                    val loot = booth.votesReady
                    votes += loot
                    totalStolenBooths++
                    booth.votesReady = 0
                    booth.stealProgress = 0f
                    booth.cooldown = 24f
                    wantedStars = (wantedStars + 1).coerceAtMost(5)
                    wantedDecayTimer = 22f
                    showBanner("VOTE CHOR ALERT! +$loot VOTES ★$wantedStars", "#FF3D00", 2.8f)
                }
            } else {
                booth.stealProgress = (booth.stealProgress - dt * 0.5f).coerceAtLeast(0f)
            }
        }

        // 5. Washing Zone Check (Clears Wanted Stars!)
        if (wantedStars > 0 && washCooldown <= 0f) {
            for (b in buildings) {
                if (b.isWashZone) {
                    val cx = b.rect.centerX()
                    val cy = b.rect.centerY()
                    if (hypot(cx - playerX, cy - playerY) < 250f) {
                        wantedStars = 0
                        playerHealth = 100f
                        washCooldown = 10f
                        showBanner("WASHING MACHINE CLEAN! 0★ CASES CLEARED!", "#00E5FF", 3.0f)
                        break
                    }
                }
            }
        }

        // 6. EC / Police Patrol AI Chase
        if (wantedStars > 0) {
            wantedDecayTimer -= dt
            if (wantedDecayTimer <= 0f) {
                wantedStars--
                wantedDecayTimer = 18f
            }

            val activeChasers = wantedStars.coerceAtMost(patrols.size)
            for (i in 0 until activeChasers) {
                val cop = patrols[i]
                if (cop.hitCooldown > 0f) cop.hitCooldown -= dt

                val dx = playerX - cop.x
                val dy = playerY - cop.y
                val dist = hypot(dx, dy).coerceAtLeast(1f)

                // Respawn far away cops closer for action
                if (dist > 1450f) {
                    val spawnAngle = Random.nextFloat() * (2f * PI.toFloat())
                    cop.x = (playerX + cos(spawnAngle) * 760f).coerceIn(120f, worldW - 120f)
                    cop.y = (playerY + sin(spawnAngle) * 760f).coerceIn(120f, worldH - 120f)
                } else {
                    val chaseSpeed = (cop.speed + wantedStars * 26f).coerceAtMost(450f)
                    cop.angle = atan2(dy, dx)
                    cop.x = (cop.x + (dx / dist) * chaseSpeed * dt).coerceIn(60f, worldW - 60f)
                    cop.y = (cop.y + (dy / dist) * chaseSpeed * dt).coerceIn(60f, worldH - 60f)

                    if (dist < 42f && cop.hitCooldown <= 0f) {
                        val dmg = if (currentVehicle != null) 10f else 18f
                        playerHealth -= dmg
                        cop.hitCooldown = 1.1f
                        showBanner("EC SQUAD HIT! ESCAPE TO WASHING ZONE!", "#FF1744", 1.8f)
                        if (playerHealth <= 0f) {
                            playerHealth = 0f
                            state = GameState.BUSTED
                        }
                    }
                }
            }
        } else {
            playerHealth = (playerHealth + dt * 6f).coerceAtMost(100f)
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

    // ==================== RENDER ENGINE ====================

    private fun renderFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            val w = width.toFloat()
            val h = height.toFloat()

            // Base Indian City Ground (Warm Sandstone / Urban Turf)
            canvas.drawColor(Color.parseColor("#2A3B2C"))

            canvas.save()
            canvas.translate(w * 0.5f - camX, h * 0.5f - camY)

            drawRoads(canvas)
            drawBuildings(canvas)
            drawPollingBooths(canvas)
            drawVotePickups(canvas)
            drawVehicles(canvas)
            drawPlayer(canvas)
            drawPatrols(canvas)

            canvas.restore()

            drawHUD(canvas, w, h)
            drawMiniMap(canvas, w)

            if (state == GameState.PLAYING) {
                drawTouchControls(canvas)
            } else if (state == GameState.TITLE) {
                drawTitleOverlay(canvas, w, h)
            } else if (state == GameState.BUSTED) {
                drawBustedOverlay(canvas, w, h)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawRoads(canvas: Canvas) {
        // Asphalt Roads
        paint.color = Color.parseColor("#26282C")
        for (i in 0..5) {
            val pos = i * blockStep
            canvas.drawRect(pos - roadWidth * 0.5f, 0f, pos + roadWidth * 0.5f, worldH, paint)
            canvas.drawRect(0f, pos - roadWidth * 0.5f, worldW, pos + roadWidth * 0.5f, paint)
        }

        // Yellow Center Dashes
        paint.color = Color.parseColor("#FFD600")
        for (i in 0..5) {
            val pos = i * blockStep
            var d = 0f
            while (d < worldW) {
                canvas.drawRect(pos - 3f, d, pos + 3f, d + 34f, paint)
                canvas.drawRect(d, pos - 3f, d + 34f, pos + 3f, paint)
                d += 72f
            }
        }
    }

    private fun drawBuildings(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        for (b in buildings) {
            // Building Shadow
            paint.color = Color.argb(95, 0, 0, 0)
            canvas.drawRoundRect(
                RectF(b.rect.left + 12f, b.rect.top + 12f, b.rect.right + 12f, b.rect.bottom + 12f),
                18f, 18f, paint
            )
            // Outer Structure
            paint.color = b.color
            canvas.drawRoundRect(b.rect, 16f, 16f, paint)

            // Inner Roof
            paint.color = b.roofColor
            val roof = RectF(b.rect.left + 26f, b.rect.top + 26f, b.rect.right - 26f, b.rect.bottom - 26f)
            canvas.drawRoundRect(roof, 10f, 10f, paint)

            // Landmark Name
            textPaint.color = if (b.isWashZone) Color.parseColor("#09090B") else Color.WHITE
            textPaint.textSize = 24f
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + 8f, textPaint)
            if (b.isWashZone) {
                textPaint.textSize = 16f
                canvas.drawText("(CLEARS WANTED STARS)", b.rect.centerX(), b.rect.centerY() + 34f, textPaint)
            }
        }
    }

    private fun drawPollingBooths(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        for (booth in booths) {
            val ready = booth.cooldown <= 0f
            // Pulsing Gold Halo if votes available
            if (ready) {
                val pulse = 48f + sin(sirenTime * 5f) * 8f
                paint.color = Color.argb(75, 255, 214, 0)
                canvas.drawCircle(booth.x, booth.y, pulse, paint)
            }

            // EVM Booth Box
            paint.color = if (ready) Color.parseColor("#FFAB00") else Color.parseColor("#616161")
            canvas.drawRoundRect(RectF(booth.x - 30f, booth.y - 24f, booth.x + 30f, booth.y + 24f), 8f, 8f, paint)

            paint.color = Color.parseColor("#111111")
            canvas.drawRect(booth.x - 16f, booth.y - 10f, booth.x + 16f, booth.y + 4f, paint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 16f
            val sub = if (ready) "EVM +${booth.votesReady}V" else "EMPTY (${booth.cooldown.toInt()}s)"
            canvas.drawText(sub, booth.x, booth.y - 34f, textPaint)

            // Steal Progress Ring
            if (booth.stealProgress > 0f) {
                strokePaint.color = Color.parseColor("#00FFAA")
                strokePaint.strokeWidth = 9f
                val arcRect = RectF(booth.x - 46f, booth.y - 46f, booth.x + 46f, booth.y + 46f)
                canvas.drawArc(arcRect, -90f, 360f * booth.stealProgress, false, strokePaint)
            }
        }
    }

    private fun drawVotePickups(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 15f
        for (p in pickups) {
            if (!p.active) continue
            paint.color = Color.parseColor("#00E676")
            canvas.drawCircle(p.x, p.y, 17f, paint)
            textPaint.color = Color.parseColor("#09090B")
            canvas.drawText("V", p.x, p.y + 6f, textPaint)
        }
    }

    private fun drawVehicles(canvas: Canvas) {
        for (v in vehicles) {
            canvas.save()
            canvas.translate(v.x, v.y)
            canvas.rotate(Math.toDegrees(v.angle.toDouble()).toFloat())

            val hw = v.type.w * 0.5f
            val hh = v.type.h * 0.5f

            // Shadow
            paint.color = Color.argb(90, 0, 0, 0)
            canvas.drawRoundRect(RectF(-hw + 4f, -hh + 4f, hw + 4f, hh + 4f), 10f, 10f, paint)

            // Body
            paint.color = v.type.bodyColor
            canvas.drawRoundRect(RectF(-hw, -hh, hw, hh), 10f, 10f, paint)

            // Accent roof / hood
            paint.color = v.type.accentColor
            canvas.drawRoundRect(RectF(-hw * 0.55f, -hh * 0.72f, hw * 0.35f, hh * 0.72f), 6f, 6f, paint)

            // Windshield
            paint.color = Color.parseColor("#263238")
            canvas.drawRect(hw * 0.36f, -hh * 0.70f, hw * 0.68f, hh * 0.70f, paint)

            // Flashing VIP Beacon on Netaji SUV
            if (v.type == VehicleType.NETAJI_SUV) {
                paint.color = if (((sirenTime * 8f).toInt() % 2) == 0) Color.RED else Color.BLUE
                canvas.drawCircle(0f, 0f, 7f, paint)
            }

            canvas.restore()
        }
    }

    private fun drawPlayer(canvas: Canvas) {
        if (currentVehicle != null) return

        canvas.save()
        canvas.translate(playerX, playerY)
        canvas.rotate(Math.toDegrees(playerAngle.toDouble()).toFloat())

        // Foot swing animation
        val swing = sin(walkPhase) * 6f
        paint.color = Color.parseColor("#4E342E")
        canvas.drawCircle(swing, -11f, 6f, paint)
        canvas.drawCircle(-swing, 11f, 6f, paint)

        // Kurta + Saffron Jacket Shoulders
        paint.color = Color.parseColor("#FF6F00")
        canvas.drawRoundRect(RectF(-12f, -20f, 12f, 20f), 10f, 10f, paint)

        // White Kurta collar
        paint.color = Color.WHITE
        canvas.drawCircle(4f, 0f, 11f, paint)

        // Head + White Hair & Beard Top-Down
        paint.color = Color.parseColor("#E0AC69")
        canvas.drawCircle(2f, 0f, 10f, paint)
        paint.color = Color.parseColor("#ECEFF1")
        canvas.drawArc(RectF(-9f, -10f, 11f, 10f), 110f, 140f, true, paint)
        // Beard front tip
        canvas.drawCircle(11f, 0f, 4.5f, paint)

        canvas.restore()
    }

    private fun drawPatrols(canvas: Canvas) {
        if (wantedStars <= 0) return
        val activeChasers = wantedStars.coerceAtMost(patrols.size)
        for (i in 0 until activeChasers) {
            val cop = patrols[i]
            canvas.save()
            canvas.translate(cop.x, cop.y)
            canvas.rotate(Math.toDegrees(cop.angle.toDouble()).toFloat())

            paint.color = Color.parseColor("#1A237E")
            canvas.drawRoundRect(RectF(-38f, -21f, 38f, 21f), 9f, 9f, paint)
            paint.color = Color.WHITE
            canvas.drawRect(-14f, -17f, 16f, 17f, paint)

            // Red/Blue Flashing Siren
            val flashRed = ((sirenTime * 10f).toInt() + i) % 2 == 0
            paint.color = if (flashRed) Color.RED else Color.parseColor("#2979FF")
            canvas.drawCircle(0f, -6f, 6f, paint)
            paint.color = if (flashRed) Color.parseColor("#2979FF") else Color.RED
            canvas.drawCircle(0f, 6f, 6f, paint)

            canvas.restore()
        }
    }

    private fun drawHUD(canvas: Canvas, w: Float, h: Float) {
        // Top-Left GTA Style Stats Pill
        paint.color = Color.argb(185, 10, 10, 14)
        canvas.drawRoundRect(RectF(24f, 20f, 390f, 138f), 20f, 20f, paint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true

        // Game Tag + Votes Counter
        textPaint.color = Color.parseColor("#FFAB00")
        textPaint.textSize = 20f
        canvas.drawText("MTA 6 • MODI THEFT AUTO", 42f, 48f, textPaint)

        textPaint.color = Color.parseColor("#00E676")
        textPaint.textSize = 30f
        canvas.drawText("VOTES: $votes", 42f, 84f, textPaint)

        // Health Bar
        paint.color = Color.parseColor("#33333A")
        canvas.drawRoundRect(RectF(42f, 96f, 220f, 114f), 8f, 8f, paint)
        paint.color = if (playerHealth > 35f) Color.parseColor("#CCFF00") else Color.parseColor("#FF1744")
        canvas.drawRoundRect(RectF(42f, 96f, 42f + 178f * (playerHealth / 100f), 114f), 8f, 8f, paint)

        // Wanted Stars
        textPaint.textSize = 24f
        val starStr = "★".repeat(wantedStars) + "☆".repeat(5 - wantedStars)
        textPaint.color = if (wantedStars > 0) Color.parseColor("#FF3D00") else Color.parseColor("#757575")
        canvas.drawText(starStr, 232f, 114f, textPaint)

        // Top Center Action Alert Banner
        if (bannerTimer > 0f && bannerText.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 24f
            val tw = textPaint.measureText(bannerText) + 48f
            paint.color = Color.argb(215, 12, 12, 16)
            canvas.drawRoundRect(RectF(w * 0.5f - tw * 0.5f, 24f, w * 0.5f + tw * 0.5f, 74f), 24f, 24f, paint)
            textPaint.color = bannerColor
            canvas.drawText(bannerText, w * 0.5f, 57f, textPaint)
        }
    }

    private fun drawMiniMap(canvas: Canvas, w: Float) {
        val mapSize = 155f
        val mx = w - mapSize - 24f
        val my = 20f

        paint.color = Color.argb(200, 15, 18, 22)
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
                canvas.drawCircle(mx + b.rect.centerX() * sx, my + b.rect.centerY() * sy, 5.5f, paint)
            }
        }

        // Polling Booths (Gold)
        paint.color = Color.parseColor("#FFD600")
        for (booth in booths) {
            if (booth.cooldown <= 0f) {
                canvas.drawCircle(mx + booth.x * sx, my + booth.y * sy, 4f, paint)
            }
        }

        // Patrol Cops (Red)
        if (wantedStars > 0) {
            paint.color = Color.RED
            for (i in 0 until wantedStars.coerceAtMost(patrols.size)) {
                canvas.drawCircle(mx + patrols[i].x * sx, my + patrols[i].y * sy, 4f, paint)
            }
        }

        // Player Dot (White/Lime)
        paint.color = Color.parseColor("#CCFF00")
        canvas.drawCircle(mx + playerX * sx, my + playerY * sy, 5.5f, paint)
    }

    private fun drawTouchControls(canvas: Canvas) {
        // Left Virtual Joystick
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(joyBaseX, joyBaseY, joyRadius, paint)
        strokePaint.color = Color.argb(140, 255, 255, 255)
        strokePaint.strokeWidth = 3f
        canvas.drawCircle(joyBaseX, joyBaseY, joyRadius, strokePaint)

        val knobX = joyBaseX + joyDx * (joyRadius * 0.65f)
        val knobY = joyBaseY + joyDy * (joyRadius * 0.65f)
        paint.color = Color.parseColor("#CCFF00")
        canvas.drawCircle(knobX, knobY, joyRadius * 0.36f, paint)

        // Right Buttons: CHORI, RIDE, NITRO
        drawActionButton(
            canvas, btnStealX, btnStealY, btnRadius,
            if (stealHeld) "#FF3D00" else "#FFAB00",
            "CHORI"
        )
        drawActionButton(
            canvas, btnRideX, btnRideY, btnRadius * 0.85f,
            if (currentVehicle != null) "#00E676" else "#2979FF",
            if (currentVehicle != null) "EXIT" else "RIDE"
        )
        drawActionButton(
            canvas, btnNitroX, btnNitroY, btnRadius * 0.85f,
            if (nitroHeld) "#CCFF00" else "#42424A",
            "NITRO"
        )
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
        paint.alpha = 215
        canvas.drawCircle(cx, cy, r, paint)
        paint.alpha = 255

        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, r, strokePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#09090B")
        textPaint.textSize = 21f
        textPaint.isFakeBoldText = true
        canvas.drawText(label, cx, cy + 8f, textPaint)
    }

    private fun drawTitleOverlay(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.argb(215, 9, 9, 14)
        canvas.drawRect(0f, 0f, w, h, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        textPaint.color = Color.parseColor("#FFAB00")
        textPaint.textSize = 74f
        canvas.drawText("MTA 6", w * 0.5f, h * 0.32f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 36f
        canvas.drawText("MODI THEFT AUTO • INDIAN OPEN WORLD", w * 0.5f, h * 0.44f, textPaint)

        textPaint.color = Color.parseColor("#B0BEC5")
        textPaint.textSize = 22f
        canvas.drawText("EVM Booths par 'CHORI' dabao • Votes jama karo • Washing Zone me Stars clear karo!", w * 0.5f, h * 0.56f, textPaint)

        paint.color = Color.parseColor("#CCFF00")
        canvas.drawRoundRect(RectF(w * 0.5f - 170f, h * 0.66f, w * 0.5f + 170f, h * 0.66f + 78f), 39f, 39f, paint)
        textPaint.color = Color.parseColor("#09090B")
        textPaint.textSize = 30f
        canvas.drawText("TAP TO PLAY", w * 0.5f, h * 0.66f + 50f, textPaint)
    }

    private fun drawBustedOverlay(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.argb(215, 24, 5, 10)
        canvas.drawRect(0f, 0f, w, h, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.color = Color.parseColor("#FF1744")
        textPaint.textSize = 68f
        canvas.drawText("BUSTED BY EC!", w * 0.5f, h * 0.38f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 28f
        canvas.drawText("Total Votes: $votes  •  Booths Looted: $totalStolenBooths", w * 0.5f, h * 0.50f, textPaint)

        paint.color = Color.parseColor("#FFAB00")
        canvas.drawRoundRect(RectF(w * 0.5f - 190f, h * 0.62f, w * 0.5f + 190f, h * 0.62f + 74f), 37f, 37f, paint)
        textPaint.color = Color.parseColor("#09090B")
        textPaint.textSize = 26f
        canvas.drawText("TAP TO RESPAWN", w * 0.5f, h * 0.62f + 46f, textPaint)
    }

    // ==================== MULTI-TOUCH INPUT ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        if (state == GameState.TITLE && action == MotionEvent.ACTION_UP) {
            startNewSession(keepVotes = false)
            return true
        }
        if (state == GameState.BUSTED && action == MotionEvent.ACTION_UP) {
            startNewSession(keepVotes = true)
            return true
        }

        val actionIdx = event.actionIndex
        val pointerId = event.getPointerId(actionIdx)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIdx)
                val y = event.getY(actionIdx)
                if (x < width * 0.45f && joyPointerId == -1) {
                    joyPointerId = pointerId
                    updateJoystick(x, y)
                } else {
                    if (hypot(x - btnRideX, y - btnRideY) <= btnRadius * 1.15f) {
                        rideRequested = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId == joyPointerId) {
                    joyPointerId = -1
                    joyDx = 0f
                    joyDy = 0f
                }
            }
        }

        // Continuously scan all active touches for joystick & held buttons
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
                if (hypot(x - btnStealX, y - btnStealY) <= btnRadius * 1.2f) stealDown = true
                if (hypot(x - btnNitroX, y - btnNitroY) <= btnRadius * 1.2f) nitroDown = true
            }
        }
        stealHeld = stealDown
        nitroHeld = nitroDown
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        val dx = x - joyBaseX
        val dy = y - joyBaseY
        val dist = hypot(dx, dy)
        if (dist > 0f) {
            val clamped = dist.coerceAtMost(joyRadius)
            joyDx = (dx / dist) * (clamped / joyRadius)
            joyDy = (dy / dist) * (clamped / joyRadius)
        }
    }
}
