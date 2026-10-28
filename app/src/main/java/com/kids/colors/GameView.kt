package com.kids.colors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class KidColor(val name: String, val argb: Int) {
    /** Definite form: أحمر → الأحمر */
    val definite get() = "ال$name"
}

private val COLORS = listOf(
    KidColor("أحمر", 0xFFE53935.toInt()),
    KidColor("أزرق", 0xFF1E88E5.toInt()),
    KidColor("أخضر", 0xFF43A047.toInt()),
    KidColor("أصفر", 0xFFFDD835.toInt()),
    KidColor("برتقالي", 0xFFFB8C00.toInt()),
    KidColor("بنفسجي", 0xFF8E24AA.toInt()),
    KidColor("وردي", 0xFFEC407A.toInt()),
)

private class Balloon(var x: Float, var y: Float, val r: Float, val color: KidColor, val speed: Float, val phase: Float) {
    var drawX = x
}

private class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val color: Int, val size: Float, val maxLife: Float, val confetti: Boolean,
    val emoji: String? = null,
) {
    var life = maxLife
    var rot = Random.nextFloat() * 360f
    val spin = Random.nextFloat() * 600f - 300f
}

private class Animal(val key: String, val emoji: String, val phrase: String, val card: Int)

private val ANIMALS = listOf(
    Animal("cow", "🐄", "البقرة تقول: مووو", 0xFFFFF3E0.toInt()),
    Animal("cat", "🐱", "القطة تقول: مياو", 0xFFFCE4EC.toInt()),
    Animal("dog", "🐶", "الكلب يقول: هاو هاو", 0xFFE3F2FD.toInt()),
    Animal("duck", "🦆", "البطة تقول: كواك كواك", 0xFFE8F5E9.toInt()),
    Animal("sheep", "🐑", "الخروف يقول: ماااء", 0xFFF3E5F5.toInt()),
    Animal("chick", "🐥", "الكتكوت يقول: صوصو", 0xFFFFFDE7.toInt()),
    Animal("frog", "🐸", "الضفدع يقول: نق نق", 0xFFE0F2F1.toInt()),
    Animal("lion", "🦁", "الأسد يقول: رااار", 0xFFFFF8E1.toInt()),
)

private class Food(val emoji: String, val name: String, val crumb: Int)

private val FOODS = listOf(
    Food("🍎", "تفاحة", 0xFFE53935.toInt()),
    Food("🍌", "موزة", 0xFFFDD835.toInt()),
    Food("🍓", "فراولة", 0xFFEC407A.toInt()),
    Food("🍪", "بسكوتة", 0xFF8D6E63.toInt()),
    Food("🍉", "بطيخ", 0xFF66BB6A.toInt()),
    Food("🥕", "جزرة", 0xFFFB8C00.toInt()),
)

private class Cloud(var x: Float, val y: Float, val s: Float, val speed: Float)

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class GameView(context: Context) : View(context) {

    private enum class Screen { MENU, BALLOONS, FIND, ANIMALS, FEED }

    private var screen = Screen.MENU
    private val sound = SoundFx()
    private val voice = Voice(context)
    private val animalSounds = AnimalSounds(context, ANIMALS.map { it.key } + listOf("chomp", "burp", "giggle"))

    private var running = false
    private var lastNs = 0L
    private var time = 0f

    private var w = 1f
    private var h = 1f
    private var u = 1f // base unit = shorter screen side

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val sky = Paint()
    private val path = Path()
    private val oval = RectF()

    private val clouds = mutableListOf<Cloud>()
    private val particles = mutableListOf<Particle>()

    // Balloons mode
    private val balloons = mutableListOf<Balloon>()
    private var spawnTimer = 0f
    private var popped = 0
    private val starsGoal = 10

    // Find-the-color mode
    private var options = listOf<KidColor>()
    private var target = COLORS[0]
    private var correctCount = 0
    private var roundLocked = false
    private var nextRoundAt = 0f
    private var winIndex = -1
    private var winTime = 0f
    private val shake = FloatArray(4)

    // Animals mode
    private val animalCols = 4
    private val cell = RectF()
    private var tappedAnimal = -1
    private var tappedAt = 0f

    // Feed mode (Bobo)
    private val fullAfter = 5
    private var downPointer = 0
    private var dragFood = -1
    private var dragPointer = -1
    private var dragX = 0f
    private var dragY = 0f
    private var mouthOpen = 0f
    private var chewUntil = 0f
    private var eaten = 0
    private var belly = 0f
    private var burpAt = -1f
    private var burpUntil = 0f
    private var tickleAt = -10f
    private var nextBlink = 2f
    private var blinkUntil = 0f
    private var lastFeedAction = 0f

    // Speech queued to start after a sound finishes
    private var pendingSay: String? = null
    private var sayAt = 0f

    // ---------------------------------------------------------------- lifecycle

    fun resume() {
        running = true
        lastNs = 0L
        postInvalidateOnAnimation()
    }

    fun pause() {
        running = false
        pendingSay = null
        voice.stop()
        animalSounds.stopAll()
    }

    fun release() {
        running = false
        sound.release()
        animalSounds.release()
        voice.release()
    }

    /** Returns true if handled (went back to menu). */
    fun goBack(): Boolean {
        if (screen == Screen.MENU) return false
        openMenu()
        return true
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat()
        h = nh.toFloat()
        u = min(w, h)
        sky.shader = LinearGradient(0f, 0f, 0f, h, 0xFF81D4FA.toInt(), 0xFFE1F5FE.toInt(), Shader.TileMode.CLAMP)
        clouds.clear()
        repeat(4) {
            clouds += Cloud(Random.nextFloat() * w, h * (0.08f + Random.nextFloat() * 0.3f), u * (0.06f + Random.nextFloat() * 0.05f), u * (0.02f + Random.nextFloat() * 0.03f))
        }
    }

    // ---------------------------------------------------------------- loop

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        time += dt

        update(dt)
        drawBackground(c)
        when (screen) {
            Screen.MENU -> drawMenu(c)
            Screen.BALLOONS -> drawBalloons(c)
            Screen.FIND -> drawFind(c)
            Screen.ANIMALS -> drawAnimals(c)
            Screen.FEED -> drawFeed(c)
        }
        drawParticles(c)
        if (screen != Screen.MENU) drawHomeButton(c)

        if (running) postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        for (cl in clouds) {
            cl.x += cl.speed * dt
            if (cl.x - cl.s * 3 > w) cl.x = -cl.s * 3
        }

        val g = u * 1.4f
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f || p.y > h + 50) { it.remove(); continue }
            when {
                p.emoji != null -> p.vx *= 0.98f
                p.confetti -> {
                    p.vx *= 0.99f
                    p.vy = min(p.vy + g * 0.25f * dt, u * 0.35f)
                }
                else -> p.vy += g * dt
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.spin * dt
        }

        when (screen) {
            Screen.BALLOONS -> updateBalloons(dt)
            Screen.FIND -> {
                for (i in shake.indices) shake[i] = (shake[i] - dt).coerceAtLeast(0f)
                if (roundLocked && time >= nextRoundAt) newRound()
            }
            Screen.FEED -> updateFeed(dt)
            Screen.MENU, Screen.ANIMALS -> Unit
        }

        pendingSay?.let {
            if (time >= sayAt) {
                voice.say(it)
                pendingSay = null
            }
        }
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                downPointer = e.getPointerId(i)
                onTap(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> if (dragFood >= 0) {
                val i = e.findPointerIndex(dragPointer)
                if (i >= 0) dragMove(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (dragFood >= 0 && e.getPointerId(e.actionIndex) == dragPointer) dragEnd()
            }
            MotionEvent.ACTION_CANCEL -> dragFood = -1
        }
        return true
    }

    private fun onTap(x: Float, y: Float) {
        if (screen != Screen.MENU && hit(x, y, homeX(), homeY(), homeR() * 1.3f)) {
            sound.tap()
            openMenu()
            return
        }
        when (screen) {
            Screen.MENU -> tapMenu(x, y)
            Screen.BALLOONS -> tapBalloons(x, y)
            Screen.FIND -> tapFind(x, y)
            Screen.ANIMALS -> tapAnimals(x, y)
            Screen.FEED -> tapFeed(x, y)
        }
    }

    private fun hit(x: Float, y: Float, cx: Float, cy: Float, r: Float) = hypot(x - cx, y - cy) <= r

    // ---------------------------------------------------------------- menu

    private val menuXs = floatArrayOf(0.14f, 0.38f, 0.62f, 0.86f)
    private fun menuR() = min(u * 0.17f, w * 0.1f)
    private fun menuY() = h * 0.55f

    private fun openMenu() {
        screen = Screen.MENU
        balloons.clear()
        particles.clear()
        pendingSay = null
        dragFood = -1
        animalSounds.stopAll()
        voice.say("هيا نلعب!")
    }

    private fun tapMenu(x: Float, y: Float) {
        val r = menuR() * 1.15f
        when {
            hit(x, y, w * menuXs[0], menuY(), r) -> startBalloons()
            hit(x, y, w * menuXs[1], menuY(), r) -> startFind()
            hit(x, y, w * menuXs[2], menuY(), r) -> startAnimals()
            hit(x, y, w * menuXs[3], menuY(), r) -> startFeed()
        }
    }

    private fun drawMenu(c: Canvas) {
        val icons = listOf("🎈" to 0xFFE53935.toInt(), "🎨" to 0xFF43A047.toInt(), "🐮" to 0xFFFB8C00.toInt(), "😋" to 0xFF8E24AA.toInt())
        icons.forEachIndexed { i, (emoji, ring) ->
            val pulse = 1f + 0.05f * sin(time * 3f + i * 2.1f)
            drawBigButton(c, w * menuXs[i], menuY(), menuR() * pulse, ring, emoji)
        }

        text.color = 0xFF01579B.toInt()
        text.textSize = u * 0.09f
        text.isFakeBoldText = true
        c.drawText(context.getString(R.string.app_name), w / 2, h * 0.2f, text)
        text.isFakeBoldText = false
    }

    private fun drawBigButton(c: Canvas, cx: Float, cy: Float, r: Float, ring: Int, emoji: String) {
        fill.color = 0x33000000
        c.drawCircle(cx, cy + r * 0.06f, r, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r, fill)
        stroke.color = ring
        stroke.strokeWidth = r * 0.1f
        c.drawCircle(cx, cy, r * 0.95f, stroke)
        drawEmoji(c, emoji, cx, cy, r * 1.0f)
    }

    private fun drawEmoji(c: Canvas, emoji: String, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        text.color = Color.BLACK
        text.alpha = alpha
        text.textSize = size
        val fm = text.fontMetrics
        c.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    private fun homeR() = u * 0.075f
    private fun homeX() = u * 0.11f
    private fun homeY() = u * 0.11f

    private fun drawHomeButton(c: Canvas) {
        fill.color = 0xCCFFFFFF.toInt()
        c.drawCircle(homeX(), homeY(), homeR(), fill)
        drawEmoji(c, "🏠", homeX(), homeY(), homeR() * 1.1f)
    }

    // ---------------------------------------------------------------- balloons

    private fun startBalloons() {
        sound.tap()
        screen = Screen.BALLOONS
        balloons.clear()
        popped = 0
        spawnTimer = 0f
        voice.say("فرقع البالونات!")
    }

    private fun updateBalloons(dt: Float) {
        spawnTimer -= dt
        if (spawnTimer <= 0f && balloons.size < 7) {
            spawnTimer = 0.7f + Random.nextFloat() * 0.6f
            val r = u * (0.09f + Random.nextFloat() * 0.04f)
            balloons += Balloon(
                x = r + Random.nextFloat() * (w - 2 * r),
                y = h + r * 2,
                r = r,
                color = COLORS.random(),
                speed = u * (0.16f + Random.nextFloat() * 0.14f),
                phase = Random.nextFloat() * 6.28f,
            )
        }
        val it = balloons.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.y -= b.speed * dt
            b.drawX = b.x + sin(time * 1.5f + b.phase) * b.r * 0.2f
            if (b.y < -b.r * 4) it.remove()
        }
    }

    private fun tapBalloons(x: Float, y: Float) {
        // Topmost (last drawn) balloon first; generous hit area for small fingers.
        val b = balloons.lastOrNull { hit(x, y, it.drawX, it.y, it.r * 1.35f) } ?: return
        balloons.remove(b)
        sound.pop()
        burst(b.drawX, b.y, b.color.argb, 20)
        popped++
        if (popped >= starsGoal) {
            popped = 0
            celebrate()
            voice.say("${b.color.name}! أحسنت يا بطل!")
        } else {
            voice.say(b.color.name)
        }
    }

    private fun drawBalloons(c: Canvas) {
        for (b in balloons) drawBalloon(c, b)
        drawStarBar(c)
    }

    private fun drawBalloon(c: Canvas, b: Balloon) {
        val x = b.drawX
        val r = b.r
        val bottom = b.y + r * 1.2f

        stroke.color = 0xFF78909C.toInt()
        stroke.strokeWidth = u * 0.006f
        path.reset()
        path.moveTo(x, bottom + r * 0.15f)
        val sway = sin(time * 3f + b.phase) * r * 0.3f
        path.quadTo(x + sway, bottom + r * 1.1f, x - sway * 0.5f, bottom + r * 2.2f)
        c.drawPath(path, stroke)

        fill.color = b.color.argb
        path.reset()
        path.moveTo(x, bottom - r * 0.05f)
        path.lineTo(x - r * 0.14f, bottom + r * 0.18f)
        path.lineTo(x + r * 0.14f, bottom + r * 0.18f)
        path.close()
        c.drawPath(path, fill)

        oval.set(x - r, b.y - r * 1.2f, x + r, bottom)
        c.drawOval(oval, fill)

        fill.color = 0x66FFFFFF
        oval.set(x - r * 0.6f, b.y - r * 0.95f, x - r * 0.2f, b.y - r * 0.3f)
        c.save()
        c.rotate(25f, oval.centerX(), oval.centerY())
        c.drawOval(oval, fill)
        c.restore()
    }

    private fun drawStarBar(c: Canvas) {
        val sr = u * 0.035f
        val gap = sr * 2.5f
        val startX = w / 2 - gap * (starsGoal - 1) / 2
        val y = u * 0.09f
        for (i in 0 until starsGoal) {
            starPath(startX + i * gap, y, sr)
            if (i < popped) {
                fill.color = 0xFFFFC107.toInt()
                c.drawPath(path, fill)
            } else {
                stroke.color = 0x99FFFFFF.toInt()
                stroke.strokeWidth = sr * 0.18f
                c.drawPath(path, stroke)
            }
        }
    }

    private fun starPath(cx: Float, cy: Float, r: Float) {
        path.reset()
        for (k in 0 until 10) {
            val rr = if (k % 2 == 0) r else r * 0.45f
            val a = -PI / 2 + k * PI / 5
            val px = cx + (rr * cos(a)).toFloat()
            val py = cy + (rr * sin(a)).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    // ---------------------------------------------------------------- find the color

    private fun startFind() {
        sound.tap()
        screen = Screen.FIND
        correctCount = 0
        newRound()
    }

    private fun newRound() {
        // Difficulty grows slowly: 2 choices → 3 → 4.
        val n = when {
            correctCount < 3 -> 2
            correctCount < 8 -> 3
            else -> 4
        }
        val prev = target
        options = COLORS.shuffled().take(n)
        target = options.filter { it != prev }.randomOrNull() ?: options.random()
        roundLocked = false
        winIndex = -1
        shake.fill(0f)
        askTarget()
    }

    private fun askTarget() = voice.say("أين اللون ${target.definite}؟")

    private fun optionCenterX(i: Int) = w * (i + 1) / (options.size + 1)
    private fun optionY() = h * 0.58f
    private fun optionR() = min(w / (options.size * 2.7f), h * 0.24f)

    private fun speakerX() = w - u * 0.11f
    private fun speakerY() = u * 0.11f

    private fun tapFind(x: Float, y: Float) {
        if (hit(x, y, speakerX(), speakerY(), homeR() * 1.3f)) {
            askTarget()
            return
        }
        if (roundLocked) return
        val r = optionR() * 1.15f
        val i = options.indices.firstOrNull { hit(x, y, optionCenterX(it), optionY(), r) } ?: return
        val picked = options[i]
        if (picked == target) {
            roundLocked = true
            correctCount++
            winIndex = i
            winTime = time
            nextRoundAt = time + 2.4f
            sound.success()
            burst(optionCenterX(i), optionY(), picked.argb, 30)
            celebrate()
            voice.say("أحسنت! هذا ${picked.definite}")
        } else {
            // No penalty: name the tapped color and repeat the question.
            shake[i] = 0.5f
            sound.gentle()
            voice.say("هذا ${picked.definite}. أين ${target.definite}؟")
        }
    }

    private fun drawFind(c: Canvas) {
        val r = optionR()
        for (i in options.indices) {
            var cx = optionCenterX(i)
            var cy = optionY()
            var rr = r
            if (shake[i] > 0f) cx += sin(shake[i] * 50f) * r * 0.12f * (shake[i] / 0.5f)
            if (i == winIndex) {
                val t = time - winTime
                cy -= abs(sin(t * 7f)) * r * 0.25f * (1f - (t / 2.4f).coerceIn(0f, 1f))
                rr *= 1.1f
            }
            fill.color = 0x33000000
            c.drawCircle(cx, cy + rr * 0.08f, rr, fill)
            fill.color = options[i].argb
            if (winIndex >= 0 && i != winIndex) fill.alpha = 90
            c.drawCircle(cx, cy, rr, fill)
            fill.alpha = 255
            fill.color = 0x55FFFFFF
            c.drawCircle(cx - rr * 0.35f, cy - rr * 0.35f, rr * 0.22f, fill)
            if (i == winIndex) drawEmoji(c, "⭐", cx, cy, rr * 0.9f)
        }

        fill.color = 0xCCFFFFFF.toInt()
        c.drawCircle(speakerX(), speakerY(), homeR(), fill)
        val s = 1f + if (roundLocked) 0f else 0.08f * sin(time * 4f)
        drawEmoji(c, "🔊", speakerX(), speakerY(), homeR() * 1.1f * s)
    }

    // ---------------------------------------------------------------- animals

    private fun startAnimals() {
        sound.tap()
        screen = Screen.ANIMALS
        tappedAnimal = -1
        voice.say("المس الحيوان واسمع صوته!")
    }

    private fun animalCell(i: Int, out: RectF) {
        val top = u * 0.22f
        val bottom = h * 0.94f
        val side = u * 0.06f
        val rows = (ANIMALS.size + animalCols - 1) / animalCols
        val cw = (w - 2 * side) / animalCols
        val ch = (bottom - top) / rows
        val col = i % animalCols
        val row = i / animalCols
        val gap = min(cw, ch) * 0.07f
        out.set(side + col * cw + gap, top + row * ch + gap, side + (col + 1) * cw - gap, top + (row + 1) * ch - gap)
    }

    private fun tapAnimals(x: Float, y: Float) {
        val i = ANIMALS.indices.firstOrNull { animalCell(it, cell); cell.contains(x, y) } ?: return
        val a = ANIMALS[i]
        tappedAnimal = i
        tappedAt = time
        // The animal speaks first, then the voice names it.
        voice.stop()
        val duration = animalSounds.play(a.key)
        pendingSay = a.phrase
        sayAt = time + duration + 0.15f
        burst(cell.centerX(), cell.centerY(), COLORS.random().argb, 12)
        burst(cell.centerX(), cell.centerY(), COLORS.random().argb, 12)
    }

    private fun drawAnimals(c: Canvas) {
        for (i in ANIMALS.indices) {
            animalCell(i, cell)
            val cx = cell.centerX()
            val cy = cell.centerY()
            val size = min(cell.width(), cell.height())
            val corner = size * 0.18f
            c.save()
            val t = time - tappedAt
            if (i == tappedAnimal && t < 0.9f) {
                val k = 1f - t / 0.9f
                val s = 1f + 0.12f * k * abs(sin(t * 12f))
                c.scale(s, s, cx, cy)
                c.rotate(sin(t * 22f) * 8f * k, cx, cy)
            }
            fill.color = 0x33000000
            cell.offset(0f, corner * 0.25f)
            c.drawRoundRect(cell, corner, corner, fill)
            cell.offset(0f, -corner * 0.25f)
            fill.color = ANIMALS[i].card
            c.drawRoundRect(cell, corner, corner, fill)
            drawEmoji(c, ANIMALS[i].emoji, cx, cy, size * 0.62f)
            c.restore()
        }
    }

    // ---------------------------------------------------------------- feed Bobo

    private fun feedR() = min(u * 0.26f, h * 0.3f)
    private fun feedX() = w * 0.5f
    private fun feedY() = h * 0.42f + sin(time * 2f) * feedR() * 0.03f
    private fun mouthX() = feedX()
    private fun mouthY() = feedY() + feedR() * 0.18f
    private fun foodS() = u * 0.11f
    private fun foodX(i: Int) = w * (i + 1) / (FOODS.size + 1)
    private fun foodY() = h * 0.87f
    private fun canEat() = burpAt < 0f && time >= burpUntil

    private fun startFeed() {
        sound.tap()
        screen = Screen.FEED
        eaten = 0
        belly = 0f
        dragFood = -1
        burpAt = -1f
        lastFeedAction = time
        voice.say("هذا بوبو! بوبو جوعان، أطعمه!")
    }

    private fun tapFeed(x: Float, y: Float) {
        if (dragFood >= 0) return
        val i = FOODS.indices.firstOrNull { hit(x, y, foodX(it), foodY(), foodS() * 0.8f) }
        if (i != null) {
            dragFood = i
            dragPointer = downPointer
            dragX = x
            dragY = y
            lastFeedAction = time
            voice.say(FOODS[i].name)
        } else if (hit(x, y, feedX(), feedY(), feedR())) {
            tickle(x, y)
        }
    }

    private fun dragMove(x: Float, y: Float) {
        dragX = x
        dragY = y
        // Eat on contact: small hands rarely release precisely over the mouth.
        if (canEat() && hit(x, y, mouthX(), mouthY(), feedR() * 0.45f)) eat(dragFood)
    }

    private fun dragEnd() {
        if (canEat() && hit(dragX, dragY, mouthX(), mouthY(), feedR() * 0.65f)) eat(dragFood) else dragFood = -1
    }

    private fun eat(i: Int) {
        val f = FOODS[i]
        dragFood = -1
        lastFeedAction = time
        voice.stop()
        val d = animalSounds.play("chomp")
        chewUntil = time + maxOf(d, 0.8f)
        burst(mouthX(), mouthY(), f.crumb, 14)
        eaten++
        if (eaten >= fullAfter) {
            burpAt = chewUntil + 0.2f
        } else {
            pendingSay = listOf("ممم! لذيذة!", "يمّي! كمان!", "زاكية! بدي كمان!").random()
            sayAt = chewUntil
        }
    }

    private fun burp() {
        burpAt = -1f
        val d = animalSounds.play("burp")
        burpUntil = time + d
        pendingSay = "شبعت! شكراً يا حبيبي!"
        sayAt = time + d + 0.2f
        eaten = 0
        hearts(mouthX(), mouthY(), 8)
        celebrate()
    }

    private fun tickle(x: Float, y: Float) {
        tickleAt = time
        lastFeedAction = time
        voice.stop()
        val d = animalSounds.play("giggle")
        pendingSay = "بتزغزغني!"
        sayAt = time + d + 0.1f
        hearts(x, y, 3)
    }

    private fun updateFeed(dt: Float) {
        val near = dragFood >= 0 && canEat() && hit(dragX, dragY, mouthX(), mouthY(), feedR() * 1.6f)
        val target = when {
            time < burpUntil -> 0.85f
            time < chewUntil -> 0.25f + 0.3f * abs(sin(time * 14f))
            near -> 1f
            else -> 0f
        }
        mouthOpen += (target - mouthOpen) * min(1f, dt * 12f)
        belly += (eaten.toFloat() / fullAfter - belly) * min(1f, dt * 4f)
        if (time >= nextBlink) {
            blinkUntil = time + 0.14f
            nextBlink = time + 2.5f + Random.nextFloat() * 3f
        }
        if (burpAt in 0f..time) burp()
        if (time - lastFeedAction > 10f && dragFood < 0) {
            lastFeedAction = time
            voice.say("بوبو جوعان! أطعمه!")
        }
    }

    private fun drawFeed(c: Canvas) {
        val r = feedR()
        val cx = feedX()
        val cy = feedY()
        val body = 0xFF4FC3F7.toInt()
        val tk = time - tickleAt
        val happy = tk < 0.9f || time < burpUntil

        c.save()
        if (tk < 0.8f) {
            val k = (1f - tk / 0.8f) * 0.07f * sin(tk * 30f)
            c.scale(1f + k, 1f - k, cx, cy + r)
        }

        // Ears, feet, fluffy body
        fill.color = body
        c.drawCircle(cx - r * 0.6f, cy - r * 0.8f, r * 0.25f, fill)
        c.drawCircle(cx + r * 0.6f, cy - r * 0.8f, r * 0.25f, fill)
        fill.color = 0xFFF8BBD0.toInt()
        c.drawCircle(cx - r * 0.6f, cy - r * 0.8f, r * 0.12f, fill)
        c.drawCircle(cx + r * 0.6f, cy - r * 0.8f, r * 0.12f, fill)
        fill.color = 0xFF29B6F6.toInt()
        oval.set(cx - r * 0.65f, cy + r * 0.85f, cx - r * 0.15f, cy + r * 1.12f)
        c.drawOval(oval, fill)
        oval.set(cx + r * 0.15f, cy + r * 0.85f, cx + r * 0.65f, cy + r * 1.12f)
        c.drawOval(oval, fill)
        fill.color = body
        for (k in 0 until 14) {
            val a = k * 2 * PI / 14
            c.drawCircle(cx + (cos(a) * r * 0.92f).toFloat(), cy + (sin(a) * r * 0.92f).toFloat(), r * 0.17f, fill)
        }
        c.drawCircle(cx, cy, r, fill)

        // Belly grows as Bobo eats
        val bs = 1f + 0.25f * belly
        fill.color = 0xFFB3E5FC.toInt()
        oval.set(cx - r * 0.45f * bs, cy + r * 0.62f - r * 0.3f * bs, cx + r * 0.45f * bs, cy + r * 0.62f + r * 0.3f * bs)
        c.drawOval(oval, fill)

        // Cheeks
        fill.color = 0x66F06292
        c.drawCircle(cx - r * 0.58f, cy + r * 0.1f, r * 0.12f, fill)
        c.drawCircle(cx + r * 0.58f, cy + r * 0.1f, r * 0.12f, fill)

        // Eyes follow the dragged food, otherwise wander
        val tx = if (dragFood >= 0) dragX else cx + sin(time * 0.7f) * r
        val ty = if (dragFood >= 0) dragY else cy + r
        val ink = 0xFF263238.toInt()
        stroke.color = ink
        stroke.strokeWidth = r * 0.05f
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * r * 0.33f
            val ey = cy - r * 0.25f
            when {
                happy -> {
                    oval.set(ex - r * 0.14f, ey - r * 0.08f, ex + r * 0.14f, ey + r * 0.16f)
                    c.drawArc(oval, 200f, 140f, false, stroke)
                }
                time < blinkUntil -> c.drawLine(ex - r * 0.15f, ey, ex + r * 0.15f, ey, stroke)
                else -> {
                    fill.color = Color.WHITE
                    c.drawCircle(ex, ey, r * 0.2f, fill)
                    val dx = tx - ex
                    val dy = ty - ey
                    val d = hypot(dx, dy).coerceAtLeast(1f)
                    val off = min(r * 0.08f, d)
                    val px = ex + dx / d * off
                    val py = ey + dy / d * off
                    fill.color = ink
                    c.drawCircle(px, py, r * 0.1f, fill)
                    fill.color = Color.WHITE
                    c.drawCircle(px - r * 0.035f, py - r * 0.035f, r * 0.03f, fill)
                }
            }
        }

        // Mouth: smile when closed, opens wide when food is near
        val mx = mouthX()
        val my = mouthY()
        if (mouthOpen < 0.08f) {
            stroke.color = 0xFF5D4037.toInt()
            oval.set(mx - r * 0.22f, my - r * 0.15f, mx + r * 0.22f, my + r * 0.1f)
            c.drawArc(oval, 20f, 140f, false, stroke)
        } else {
            val half = r * (0.04f + 0.22f * mouthOpen)
            fill.color = 0xFF8E2430.toInt()
            oval.set(mx - r * 0.26f, my - half * 0.6f, mx + r * 0.26f, my + half * 1.4f)
            c.drawOval(oval, fill)
            if (mouthOpen > 0.3f) {
                fill.color = 0xFFF48FB1.toInt()
                oval.set(mx - r * 0.14f, my + half * 1.4f - r * 0.14f * mouthOpen, mx + r * 0.14f, my + half * 1.3f)
                c.drawOval(oval, fill)
            }
        }
        c.restore()

        // Food tray
        val s = foodS()
        fill.color = 0x88FFFFFF.toInt()
        oval.set(w * 0.08f, foodY() - s * 0.75f, w * 0.92f, foodY() + s * 0.75f)
        c.drawRoundRect(oval, s * 0.5f, s * 0.5f, fill)
        for (i in FOODS.indices) {
            if (i == dragFood) continue
            val bob = sin(time * 3f + i) * s * 0.06f
            drawEmoji(c, FOODS[i].emoji, foodX(i), foodY() + bob, s)
        }
        if (dragFood >= 0) drawEmoji(c, FOODS[dragFood].emoji, dragX, dragY, s * 1.3f)
    }

    // ---------------------------------------------------------------- effects

    private fun burst(x: Float, y: Float, color: Int, count: Int) {
        repeat(count) {
            val a = Random.nextFloat() * 2 * PI.toFloat()
            val sp = u * (0.4f + Random.nextFloat() * 0.8f)
            particles += Particle(
                x, y, cos(a) * sp, sin(a) * sp - u * 0.3f,
                color, u * (0.012f + Random.nextFloat() * 0.015f), 0.9f + Random.nextFloat() * 0.4f, false,
            )
        }
    }

    private fun hearts(x: Float, y: Float, count: Int) {
        repeat(count) {
            particles += Particle(
                x, y, (Random.nextFloat() - 0.5f) * u * 0.5f, -u * (0.2f + Random.nextFloat() * 0.25f),
                Color.WHITE, u * (0.06f + Random.nextFloat() * 0.04f), 1.6f, false,
                emoji = listOf("❤️", "💖", "💛").random(),
            )
        }
    }

    private fun celebrate() {
        repeat(90) {
            particles += Particle(
                Random.nextFloat() * w, -Random.nextFloat() * h * 0.4f,
                (Random.nextFloat() - 0.5f) * u * 0.4f, u * (0.1f + Random.nextFloat() * 0.2f),
                COLORS.random().argb, u * (0.012f + Random.nextFloat() * 0.012f), 3.5f, true,
            )
        }
    }

    private fun drawParticles(c: Canvas) {
        for (p in particles) {
            fill.color = p.color
            fill.alpha = (255 * (p.life / p.maxLife).coerceIn(0f, 1f)).toInt()
            if (p.emoji != null) {
                drawEmoji(c, p.emoji, p.x, p.y, p.size, fill.alpha)
            } else if (p.confetti) {
                c.save()
                c.rotate(p.rot, p.x, p.y)
                c.drawRect(p.x - p.size, p.y - p.size * 0.5f, p.x + p.size, p.y + p.size * 0.5f, fill)
                c.restore()
            } else {
                c.drawCircle(p.x, p.y, p.size, fill)
            }
        }
        fill.alpha = 255
    }

    private fun drawBackground(c: Canvas) {
        c.drawRect(0f, 0f, w, h, sky)
        fill.color = 0xE6FFFFFF.toInt()
        for (cl in clouds) {
            c.drawCircle(cl.x, cl.y, cl.s, fill)
            c.drawCircle(cl.x + cl.s * 0.9f, cl.y + cl.s * 0.2f, cl.s * 0.8f, fill)
            c.drawCircle(cl.x - cl.s * 0.9f, cl.y + cl.s * 0.25f, cl.s * 0.7f, fill)
        }
        fill.color = 0xFF8BC34A.toInt()
        c.drawRect(0f, h * 0.92f, w, h, fill)
    }
}
