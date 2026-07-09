package com.smart.keyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.view.Gravity
import android.view.ViewGroup
import android.content.Context
import android.content.SharedPreferences
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.graphics.drawable.GradientDrawable

/**
 * SmartKeyboardService is a native system-level Input Method Service (IME).
 * Renders a highly responsive virtual keyboard, handles language toggles,
 * layouts (QWERTY, Cheonjiin, Naratgul), and manages a beautiful Slate suggestion bar.
 */
class SmartKeyboardService : InputMethodService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val automaton = HangulAutomaton()
    
    // States
    private var currentLanguage = "ko" // "ko" or "en"
    private var activeKoreanLayout = "cheonjiin" // "qwerty", "cheonjiin", "naratgul" (default matching web)
    private var isShiftActive = false
    private val koJamoBuffer = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "activeKoreanLayout") {
            loadSettings()
            if (::layoutIndicatorBtn.isInitialized) {
                layoutIndicatorBtn.text = getLayoutLabel()
            }
            if (::keysContainer.isInitialized) {
                buildKeysLayout()
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)
        activeKoreanLayout = prefs.getString("activeKoreanLayout", "cheonjiin") ?: "cheonjiin"
    }

    // Layout Containers
    private lateinit var keysContainer: LinearLayout
    private lateinit var suggestionBtn1: Button
    private lateinit var suggestionBtn2: Button
    private lateinit var suggestionBtn3: Button
    private lateinit var layoutIndicatorBtn: Button

    companion object {
        val DICTIONARY_KO = listOf(
            "안녕하세요", "감사합니다", "반갑습니다", "오늘", "내일", "어제", "지금", "어디야", "뭐해",
            "바빠요", "사랑해", "화이팅", "좋은 하루", "축하합니다", "죄송합니다", "맞춤법", "자동완성",
            "스마트폰", "컴퓨터", "키보드", "알겠습니다", "인공지능", "대한민국", "한국어", "영어"
        )

        val DICTIONARY_EN = listOf(
            "hello", "thanks", "thank you", "welcome", "today", "tomorrow", "yesterday", "now", "where", "busy",
            "what", "doing", "love", "congrats", "sorry", "autocorrect", "autocomplete", "smartphone", "computer",
            "keyboard", "custom", "artificial", "intelligence", "perfect", "awesome", "amazing", "beautiful"
        )
    }

    override fun onCreateInputView(): View {
        loadSettings()
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0f172a")) // Slate-900 canvas
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Suggestion & Tool Bar
        rootLayout.addView(createToolbarRow())

        // 2. Main Keys Container
        keysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setPadding(6, 12, 6, 12)
            }
        }
        rootLayout.addView(keysContainer)

        // 3. Space Bar & System Actions Row
        rootLayout.addView(createSpaceRow())

        // Initial render
        buildKeysLayout()
        updateSuggestions()

        return rootLayout
    }

    private fun createToolbarRow(): LinearLayout {
        val density = resources.displayMetrics.density
        val paddingPx = (8 * density).toInt()

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1e293b")) // Slate-800 suggestion bar
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            )
            setPadding(paddingPx, 0, paddingPx, 0)
        }

        // Suggestion Chips Container
        val chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.0f
            )
        }

        val chipParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ).apply {
            setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }

        suggestionBtn1 = Button(this).apply {
            layoutParams = chipParams
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            textSize = 12f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = createGradientDrawable("#334155", 14f)
            setOnClickListener { handleSuggestionClick(text.toString()) }
        }

        suggestionBtn2 = Button(this).apply {
            layoutParams = chipParams
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            textSize = 12f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = createGradientDrawable("#334155", 14f)
            setOnClickListener { handleSuggestionClick(text.toString()) }
        }

        suggestionBtn3 = Button(this).apply {
            layoutParams = chipParams
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            textSize = 12f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = createGradientDrawable("#334155", 14f)
            setOnClickListener { handleSuggestionClick(text.toString()) }
        }

        chipsContainer.addView(suggestionBtn1)
        chipsContainer.addView(suggestionBtn2)
        chipsContainer.addView(suggestionBtn3)
        toolbar.addView(chipsContainer)

        // Layout Indicator/Switcher on Toolbar
        layoutIndicatorBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (90 * density).toInt(),
                (28 * density).toInt()
            ).apply {
                setMargins((4 * density).toInt(), 0, 0, 0)
            }
            text = getLayoutLabel()
            setTextColor(android.graphics.Color.WHITE)
            textSize = 10f
            background = createGradientDrawable("#6366f1", 6f) // Indigo accent for switcher
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                cycleLayout()
            }
        }
        toolbar.addView(layoutIndicatorBtn)

        return toolbar
    }

    private fun createSpaceRow(): LinearLayout {
        val density = resources.displayMetrics.density
        val paddingPx = (6 * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setPadding(paddingPx, 0, paddingPx, paddingPx)
            }
        }

        // Language toggle (한/영)
        val langBtn = Button(this).apply {
            text = "한 / EN"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            background = createGradientDrawable("#334155", 8f) // Slate-700
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                currentLanguage = if (currentLanguage == "ko") "en" else "ko"
                layoutIndicatorBtn.visibility = if (currentLanguage == "ko") View.VISIBLE else View.GONE
                buildKeysLayout()
                updateSuggestions()
            }
        }
        row.addView(langBtn)

        // Space bar
        val spaceBtn = Button(this).apply {
            text = if (currentLanguage == "ko") "스페이스" else "Space"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            background = createGradientDrawable("#1e293b", 8f) // Slate-800
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5.0f).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                commitText(" ")
                updateSuggestions()
            }
        }
        row.addView(spaceBtn)

        // Enter key
        val enterBtn = Button(this).apply {
            text = "Enter"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            background = createGradientDrawable("#4f46e5", 8f) // Deep indigo
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                val ic: InputConnection = currentInputConnection ?: return@setOnClickListener
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                updateSuggestions()
            }
        }
        row.addView(enterBtn)

        return row
    }

    private fun buildKeysLayout() {
        keysContainer.removeAllViews()
        val density = resources.displayMetrics.density

        if (currentLanguage == "en") {
            // English QWERTY
            val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
            val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
            val row3 = listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫")
            keysContainer.addView(createRow(row1))
            keysContainer.addView(createRow(row2))
            keysContainer.addView(createRow(row3))
        } else {
            // Korean layouts
            when (activeKoreanLayout) {
                "qwerty" -> {
                    val row1 = if (isShiftActive) {
                        listOf("ㅃ", "ㅉ", "ㄷ", "ㄲ", "ㅆ", "ㅛ", "ㅕ", "ㅑ", "ㅒ", "ㅖ")
                    } else {
                        listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ")
                    }
                    val row2 = listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ")
                    val row3 = listOf("⇧", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ", "⌫")
                    keysContainer.addView(createRow(row1))
                    keysContainer.addView(createRow(row2))
                    keysContainer.addView(createRow(row3))
                }
                "cheonjiin" -> {
                    keysContainer.addView(createRow(listOf("ㅣ", "·", "ㅡ")))
                    keysContainer.addView(createRow(listOf("ㄱㅋ", "ㄴㄹ", "ㄷㅌ")))
                    keysContainer.addView(createRow(listOf("ㅂㅍ", "ㅅㅎ", "ㅈㅊ")))
                    keysContainer.addView(createRow(listOf("획추가", "ㅇㅁ", "쌍자음", "⌫")))
                }
                "naratgul" -> {
                    keysContainer.addView(createRow(listOf("ㄱ", "ㄴ", "ㄷ", "ㅏ", "ㅓ")))
                    keysContainer.addView(createRow(listOf("ㄹ", "ㅁ", "ㅅ", "ㅗ", "ㅜ")))
                    keysContainer.addView(createRow(listOf("ㅇ", "ㅈ", "ㅊ", "ㅡ", "ㅣ")))
                    keysContainer.addView(createRow(listOf("획추가", "쌍자음", "⌫")))
                }
                "geomjigeul" -> {
                    keysContainer.addView(createRow(listOf("ㄱ", "ㄴ", "ㄷ", "ㅗ", "ㅏ")))
                    keysContainer.addView(createRow(listOf("ㄹ", "ㅁ", "ㅂ", "ㅡ", "ㅣ")))
                    keysContainer.addView(createRow(listOf("ㅅ", "ㅇ", "ㅈ", "ㅜ", "ㅓ")))
                    keysContainer.addView(createRow(listOf("획추가", "쌍자음", "⌫")))
                }
            }
        }
    }

    private fun createRow(keys: List<String>): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
        }

        for (key in keys) {
            val keyButton = Button(this).apply {
                text = if (isShiftActive && currentLanguage == "en" && key.length == 1) key.uppercase() else key
                setTextColor(android.graphics.Color.WHITE)
                textSize = if (key.length > 1) 13f else 18f
                isAllCaps = false
                setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())

                // Style based on function vs character
                val isControl = key == "⇧" || key == "⌫" || key == "획추가" || key == "쌍자음"
                val bgColor = if (isControl) "#475569" else "#1e293b" // slate-600 vs slate-800
                background = createGradientDrawable(bgColor, 8f)

                val weight = if (isControl) 1.4f else 1.0f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    weight
                ).apply {
                    setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                }

                val swipableConsonants = setOf("ㄱ", "ㄷ", "ㅂ", "ㅈ", "ㅇ", "ㅅ")
                if (activeKoreanLayout == "geomjigeul" && swipableConsonants.contains(key)) {
                    setOnTouchListener(object : View.OnTouchListener {
                        private var startX = 0f
                        private var startY = 0f
                        private var hasSwiped = false

                        override fun onTouch(v: View, event: MotionEvent): Boolean {
                            try {
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        startX = event.x
                                        startY = event.y
                                        hasSwiped = false
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val diffX = event.x - startX
                                        if (Math.abs(diffX) > 40 * density && !hasSwiped) {
                                            hasSwiped = true
                                            val isLeft = diffX < 0
                                            val resolved = if (isLeft) {
                                                when (key) {
                                                    "ㄱ" -> "ㅋ"
                                                    "ㄷ" -> "ㅌ"
                                                    "ㅂ" -> "ㅍ"
                                                    "ㅈ" -> "ㅊ"
                                                    "ㅇ" -> "ㅎ"
                                                    else -> null
                                                }
                                            } else {
                                                when (key) {
                                                    "ㄱ" -> "ㄲ"
                                                    "ㄷ" -> "ㄸ"
                                                    "ㅂ" -> "ㅃ"
                                                    "ㅈ" -> "ㅉ"
                                                    "ㅅ" -> "ㅆ"
                                                    else -> null
                                                }
                                            }
                                            if (resolved != null) {
                                                triggerHapticFeedback()
                                                koJamoBuffer.add(resolved)
                                                val composed = automaton.assembleJamos(resolveGeomjigeulBuffer(koJamoBuffer))
                                                currentInputConnection?.setComposingText(composed, 1)
                                                updateSuggestions()
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        if (!hasSwiped) {
                                            handleKeyPress(key)
                                        }
                                        v.performClick()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            return true
                        }
                    })
                } else {
                    setOnClickListener {
                        try {
                            handleKeyPress(key)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            row.addView(keyButton)
        }

        return row
    }

    private fun handleKeyPress(key: String) {
        try {
            triggerHapticFeedback()
            val ic: InputConnection = currentInputConnection ?: return

            when (key) {
                "⌫" -> {
                    if (currentLanguage == "ko" && koJamoBuffer.isNotEmpty()) {
                        koJamoBuffer.removeAt(koJamoBuffer.size - 1)
                        val composed = when (activeKoreanLayout) {
                            "cheonjiin" -> automaton.assembleJamos(resolveCheonjiinBuffer(koJamoBuffer))
                            "geomjigeul" -> automaton.assembleJamos(resolveGeomjigeulBuffer(koJamoBuffer))
                            else -> automaton.assembleJamos(koJamoBuffer)
                        }

                        if (composed.isEmpty()) {
                            ic.setComposingText("", 1)
                        } else {
                            ic.setComposingText(composed, 1)
                        }
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                    updateSuggestions()
                }
                "⇧" -> {
                    isShiftActive = !isShiftActive
                    buildKeysLayout()
                }
                "획추가" -> {
                    if (currentLanguage == "ko" && koJamoBuffer.isNotEmpty()) {
                        val lastIdx = koJamoBuffer.size - 1
                        val lastVal = koJamoBuffer[lastIdx]
                        val strokeMap = mapOf(
                            "ㄱ" to "ㅋ", "ㅋ" to "ㄲ", "ㄴ" to "ㄷ", "ㄷ" to "ㅌ", "ㅌ" to "ㄸ",
                            "ㅁ" to "ㅂ", "ㅂ" to "ㅍ", "ㅍ" to "ㅃ", "ㅅ" to "ㅈ", "ㅈ" to "ㅊ",
                            "ㅊ" to "ㅉ", "ㅇ" to "ㅎ", "ㅏ" to "ㅑ", "ㅓ" to "여", "ㅗ" to "요",
                            "ㅜ" to "유", "ㅐ" to "ㅒ", "ㅔ" to "ㅖ", "ㅑ" to "ㅏ", "ㅕ" to "ㅓ",
                            "용" to "요", "ㅛ" to "ㅗ", "ㅠ" to "ㅜ"
                        )
                        if (strokeMap.containsKey(lastVal)) {
                            koJamoBuffer[lastIdx] = strokeMap[lastVal]!!
                        }
                        val composed = when (activeKoreanLayout) {
                            "cheonjiin" -> automaton.assembleJamos(resolveCheonjiinBuffer(koJamoBuffer))
                            "geomjigeul" -> automaton.assembleJamos(resolveGeomjigeulBuffer(koJamoBuffer))
                            else -> automaton.assembleJamos(koJamoBuffer)
                        }
                        ic.setComposingText(composed, 1)
                    }
                    updateSuggestions()
                }
                "쌍자음" -> {
                    if (currentLanguage == "ko" && koJamoBuffer.isNotEmpty()) {
                        val lastIdx = koJamoBuffer.size - 1
                        val lastVal = koJamoBuffer[lastIdx]
                        val doubleMap = mapOf(
                            "ㄱ" to "ㄲ", "ㄷ" to "ㄸ", "ㅂ" to "ㅃ", "ㅅ" to "ㅆ", "ㅈ" to "ㅉ",
                            "ㄲ" to "ㄱ", "ㄸ" to "ㄷ", "ㅃ" to "ㅂ", "ㅆ" to "ㅅ", "ㅉ" to "ㅈ"
                        )
                        if (doubleMap.containsKey(lastVal)) {
                            koJamoBuffer[lastIdx] = doubleMap[lastVal]!!
                        }
                        val composed = when (activeKoreanLayout) {
                            "cheonjiin" -> automaton.assembleJamos(resolveCheonjiinBuffer(koJamoBuffer))
                            "geomjigeul" -> automaton.assembleJamos(resolveGeomjigeulBuffer(koJamoBuffer))
                            else -> automaton.assembleJamos(koJamoBuffer)
                        }
                        ic.setComposingText(composed, 1)
                    }
                    updateSuggestions()
                }
                "ㄱㅋ", "ㄴㄹ", "ㄷㅌ", "ㅂㅍ", "ㅅㅎ", "ㅈㅊ", "ㅇㅁ" -> {
                    // Cheonjiin consonant clusters
                    val group = when (key) {
                        "ㄱㅋ" -> listOf("ㄱ", "ㅋ", "ㄲ")
                        "ㄴㄹ" -> listOf("ㄴ", "ㄹ")
                        "ㄷㅌ" -> listOf("ㄷ", "ㅌ", "ㄸ")
                        "ㅂㅍ" -> listOf("ㅂ", "ㅍ", "ㅃ")
                        "ㅅㅎ" -> listOf("ㅅ", "ㅎ", "ㅆ")
                        "ㅈㅊ" -> listOf("ㅈ", "ㅊ", "ㅉ")
                        else -> listOf("ㅇ", "ㅁ")
                    }
                    cycleCheonjiin(group)
                    val composed = automaton.assembleJamos(resolveCheonjiinBuffer(koJamoBuffer))
                    ic.setComposingText(composed, 1)
                    updateSuggestions()
                }
                else -> {
                    // Regular keys
                    if (currentLanguage == "en") {
                        val charToCommit = if (isShiftActive) key.uppercase() else key.lowercase()
                        ic.commitText(charToCommit, 1)
                        if (isShiftActive) {
                            isShiftActive = false
                            buildKeysLayout()
                        }
                    } else {
                        // Korean Jamos
                        koJamoBuffer.add(key)
                        val composed = when (activeKoreanLayout) {
                            "cheonjiin" -> automaton.assembleJamos(resolveCheonjiinBuffer(koJamoBuffer))
                            "geomjigeul" -> automaton.assembleJamos(resolveGeomjigeulBuffer(koJamoBuffer))
                            else -> automaton.assembleJamos(koJamoBuffer)
                        }
                        ic.setComposingText(composed, 1)

                        if (isShiftActive) {
                            isShiftActive = false
                            buildKeysLayout()
                        }
                    }
                    updateSuggestions()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cycleCheonjiin(group: List<String>) {
        if (koJamoBuffer.isNotEmpty()) {
            val last = koJamoBuffer.last()
            val idx = group.indexOf(last)
            if (idx != -1) {
                val nextIdx = (idx + 1) % group.size
                koJamoBuffer[koJamoBuffer.size - 1] = group[nextIdx]
                return
            }
        }
        koJamoBuffer.add(group[0])
    }

    private fun resolveCheonjiinBuffer(buffer: List<String>): List<String> {
        val resolved = mutableListOf<String>()
        val tempVowels = mutableListOf<String>()

        for (item in buffer) {
            if (item == "ㅣ" || item == "·" || item == "ㅡ") {
                tempVowels.add(item)
            } else {
                if (tempVowels.isNotEmpty()) {
                    val composedVowel = automaton.composeCheonjiinVowels(tempVowels)
                    resolved.addAll(composedVowel.map { it.toString() })
                    tempVowels.clear()
                }
                resolved.add(item)
            }
        }
        if (tempVowels.isNotEmpty()) {
            val composedVowel = automaton.composeCheonjiinVowels(tempVowels)
            resolved.addAll(composedVowel.map { it.toString() })
        }
        return resolved
    }

    private fun resolveGeomjigeulBuffer(buffer: List<String>): List<String> {
        val resolved = mutableListOf<String>()
        var i = 0
        while (i < buffer.size) {
            if (i + 1 < buffer.size) {
                val combined = buffer[i] + buffer[i + 1]
                val combinedVowel = when (combined) {
                    "ㅣㅏ" -> "야"
                    "ㅣㅓ" -> "여"
                    "ㅣㅗ" -> "요"
                    "ㅣㅜ" -> "유"
                    "ㅡㅣ" -> "의"
                    else -> null
                }
                if (combinedVowel != null) {
                    // Let the automaton's Jamo layout or standard Hangul system process it
                    resolved.addAll(combinedVowel.map { it.toString() })
                    i += 2
                    continue
                }
            }
            resolved.add(buffer[i])
            i++
        }
        return resolved
    }

    private fun getCurrentWord(): String {
        val ic: InputConnection = currentInputConnection ?: return ""
        if (currentLanguage == "ko" && koJamoBuffer.isNotEmpty()) {
            return when (activeKoreanLayout) {
                "cheonjiin" -> automaton.assembleJamos(resolveCheonjiinBuffer(koJamoBuffer))
                "geomjigeul" -> automaton.assembleJamos(resolveGeomjigeulBuffer(koJamoBuffer))
                else -> automaton.assembleJamos(koJamoBuffer)
            }
        }

        val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
        if (textBefore.isEmpty()) return ""

        val lastSpaceIdx = textBefore.lastIndexOf(' ')
        return if (lastSpaceIdx == -1) {
            textBefore
        } else {
            textBefore.substring(lastSpaceIdx + 1)
        }
    }

    private fun updateSuggestions() {
        val currentWord = getCurrentWord().trim()
        val dict = if (currentLanguage == "ko") DICTIONARY_KO else DICTIONARY_EN

        val candidates = mutableListOf<String>()
        if (currentWord.isNotEmpty()) {
            for (word in dict) {
                if (word.startsWith(currentWord) && word != currentWord) {
                    candidates.add(word)
                    if (candidates.size >= 3) break
                }
            }
        }

        // Fill up to 3 candidates with helpful defaults
        val defaults = if (currentLanguage == "ko") {
            listOf("안녕하세요", "감사합니다", "알겠습니다")
        } else {
            listOf("hello", "thanks", "welcome")
        }

        for (defaultWord in defaults) {
            if (candidates.size >= 3) break
            if (!candidates.contains(defaultWord) && defaultWord != currentWord) {
                candidates.add(defaultWord)
            }
        }

        if (::suggestionBtn1.isInitialized) {
            suggestionBtn1.text = if (candidates.size > 0) candidates[0] else ""
            suggestionBtn1.visibility = if (candidates.size > 0 && candidates[0].isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }
        if (::suggestionBtn2.isInitialized) {
            suggestionBtn2.text = if (candidates.size > 1) candidates[1] else ""
            suggestionBtn2.visibility = if (candidates.size > 1 && candidates[1].isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }
        if (::suggestionBtn3.isInitialized) {
            suggestionBtn3.text = if (candidates.size > 2) candidates[2] else ""
            suggestionBtn3.visibility = if (candidates.size > 2 && candidates[2].isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun handleSuggestionClick(suggestion: String) {
        triggerHapticFeedback()
        val ic: InputConnection = currentInputConnection ?: return

        if (currentLanguage == "ko" && koJamoBuffer.isNotEmpty()) {
            ic.commitText(suggestion + " ", 1)
            koJamoBuffer.clear()
        } else {
            val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
            if (textBefore.isNotEmpty()) {
                val lastSpaceIdx = textBefore.lastIndexOf(' ')
                val lenToDelete = if (lastSpaceIdx == -1) {
                    textBefore.length
                } else {
                    textBefore.length - lastSpaceIdx - 1
                }
                ic.deleteSurroundingText(lenToDelete, 0)
            }
            ic.commitText(suggestion + " ", 1)
        }
        updateSuggestions()
    }

    private fun cycleLayout() {
        activeKoreanLayout = when (activeKoreanLayout) {
            "qwerty" -> "cheonjiin"
            "cheonjiin" -> "naratgul"
            "naratgul" -> "geomjigeul"
            else -> "qwerty"
        }
        val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("activeKoreanLayout", activeKoreanLayout).commit()
        layoutIndicatorBtn.text = getLayoutLabel()
        buildKeysLayout()
    }

    private fun getLayoutLabel(): String {
        return when (activeKoreanLayout) {
            "qwerty" -> "두벌식 (QWERTY)"
            "cheonjiin" -> "천지인 (Cheonjiin)"
            "naratgul" -> "나랏글 (Naratgul)"
            "geomjigeul" -> "검지글 (Geomjigeul)"
            else -> "두벌식"
        }
    }

    private fun commitActiveComposition() {
        val ic: InputConnection = currentInputConnection ?: return
        if (currentLanguage == "ko" && koJamoBuffer.isNotEmpty()) {
            val composed = when (activeKoreanLayout) {
                "cheonjiin" -> automaton.assembleJamos(resolveCheonjiinBuffer(koJamoBuffer))
                "geomjigeul" -> automaton.assembleJamos(resolveGeomjigeulBuffer(koJamoBuffer))
                else -> automaton.assembleJamos(koJamoBuffer)
            }
            ic.commitText(composed, 1)
            koJamoBuffer.clear()
        }
    }

    private fun commitText(text: String) {
        val ic: InputConnection = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    private fun triggerHapticFeedback() {
        try {
            val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)
            val vibrateOnPress = prefs.getBoolean("vibrateOnPress", true)
            if (!vibrateOnPress) return

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createGradientDrawable(colorHex: String, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(android.graphics.Color.parseColor(colorHex))
        val density = resources.displayMetrics.density
        drawable.cornerRadius = radiusDp * density
        return drawable
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        loadSettings()
        if (::layoutIndicatorBtn.isInitialized) {
            layoutIndicatorBtn.text = getLayoutLabel()
        }
        if (::keysContainer.isInitialized) {
            buildKeysLayout()
        }
        koJamoBuffer.clear()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        koJamoBuffer.clear()
    }
}
