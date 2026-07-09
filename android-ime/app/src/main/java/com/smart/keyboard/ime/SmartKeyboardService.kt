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
    private var isSymbolsActive = false
    private val koJamoBuffer = mutableListOf<String>()
    private var isShowingPhrases = false
    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        speechRecognizer?.destroy()
        speechRecognizer = null
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
    private lateinit var spaceRowView: LinearLayout
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
        spaceRowView = createSpaceRow()
        rootLayout.addView(spaceRowView)

        // Initial render
        buildKeysLayout()
        updateSuggestions()

        return rootLayout
    }

    private fun createToolbarRow(): LinearLayout {
        val density = resources.displayMetrics.density
        val paddingPx = (8 * density).toInt()
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val barHeightDp = if (isLandscape) 32 else 44

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1e293b")) // Slate-800 suggestion bar
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (barHeightDp * density).toInt()
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
            textSize = if (isLandscape) 10f else 12f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = createGradientDrawable("#334155", 14f)
            setOnClickListener { handleSuggestionClick(text.toString()) }
        }

        suggestionBtn2 = Button(this).apply {
            layoutParams = chipParams
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            textSize = if (isLandscape) 10f else 12f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = createGradientDrawable("#334155", 14f)
            setOnClickListener { handleSuggestionClick(text.toString()) }
        }

        suggestionBtn3 = Button(this).apply {
            layoutParams = chipParams
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            textSize = if (isLandscape) 10f else 12f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = createGradientDrawable("#334155", 14f)
            setOnClickListener { handleSuggestionClick(text.toString()) }
        }

        chipsContainer.addView(suggestionBtn1)
        chipsContainer.addView(suggestionBtn2)
        chipsContainer.addView(suggestionBtn3)
        toolbar.addView(chipsContainer)

        val btnHeightDp = if (isLandscape) 24 else 28

        // Layout Indicator/Switcher on Toolbar
        layoutIndicatorBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (90 * density).toInt(),
                (btnHeightDp * density).toInt()
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

        // Settings Button next to layout indicator to open the Control Center Activity
        val settingsBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (32 * density).toInt(),
                (btnHeightDp * density).toInt()
            ).apply {
                setMargins((4 * density).toInt(), 0, 0, 0)
            }
            text = "⚙️"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            background = createGradientDrawable("#475569", 6f)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                triggerHapticFeedback()
                try {
                    val intent = android.content.Intent(this@SmartKeyboardService, MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@SmartKeyboardService, "관리 센터를 실행할 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        toolbar.addView(settingsBtn)

        return toolbar
    }

    private fun createSpaceRow(): LinearLayout {
        val density = resources.displayMetrics.density
        val paddingPx = (6 * density).toInt()
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val btnPaddingY = if (isLandscape) (4 * density).toInt() else (12 * density).toInt()

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

        // 1. Symbol/Emoji button (?123 / !#%)
        val symBtn = Button(this).apply {
            text = if (isSymbolsActive) "ABC" else "!#%"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = createGradientDrawable("#475569", 8f) // Slate-600
            setPadding(0, btnPaddingY, 0, btnPaddingY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                isSymbolsActive = !isSymbolsActive
                buildKeysLayout()
            }
        }
        row.addView(symBtn)

        // 2. Language toggle (한/영)
        val langBtn = Button(this).apply {
            text = "한/EN"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            isAllCaps = false
            background = createGradientDrawable("#334155", 8f) // Slate-700
            setPadding(0, btnPaddingY, 0, btnPaddingY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                isSymbolsActive = false
                currentLanguage = if (currentLanguage == "ko") "en" else "ko"
                layoutIndicatorBtn.visibility = if (currentLanguage == "ko") View.VISIBLE else View.GONE
                buildKeysLayout()
                updateSuggestions()
            }
        }
        row.addView(langBtn)

        // 3. Space bar
        val spaceBtn = Button(this).apply {
            text = if (currentLanguage == "ko") "스페이스" else "Space"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = createGradientDrawable("#1e293b", 8f) // Slate-800
            setPadding(0, btnPaddingY, 0, btnPaddingY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3.2f).apply {
                setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                commitText(" ")
                updateSuggestions()
            }
        }
        row.addView(spaceBtn)

        // 4. Voice Input (Mic) button
        val micBtn = Button(this).apply {
            text = "🎙"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = createGradientDrawable("#334155", 8f) // Slate-700
            setPadding(0, btnPaddingY, 0, btnPaddingY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                startVoiceRecognition()
            }
        }
        row.addView(micBtn)

        // 5. Layout Switcher Menu button (change layout directly without settings)
        val layoutBtn = Button(this).apply {
            text = "배열"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            isAllCaps = false
            background = createGradientDrawable("#334155", 8f) // Slate-700
            setPadding(0, btnPaddingY, 0, btnPaddingY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            setOnClickListener {
                triggerHapticFeedback()
                commitActiveComposition()
                cycleLayout()
                val label = getLayoutLabel()
                android.widget.Toast.makeText(this@SmartKeyboardService, "키보드 배열: $label", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(layoutBtn)

        // 6. Enter key
        val enterBtn = Button(this).apply {
            text = "Enter"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            isAllCaps = false
            background = createGradientDrawable("#4f46e5", 8f) // Deep indigo
            setPadding(0, btnPaddingY, 0, btnPaddingY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
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

    private fun startVoiceRecognition() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "마이크 권한이 비활성화되어 있습니다. 관리 센터 앱에서 권한을 허용해 주세요.", android.widget.Toast.LENGTH_LONG).show()
            try {
                val intent = android.content.Intent(this, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {}
            return
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
                speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : android.speech.RecognitionListener {
                        override fun onReadyForSpeech(params: android.os.Bundle?) {
                            android.widget.Toast.makeText(this@SmartKeyboardService, "🎤 음성 입력 준비 완료! 말씀하세요...", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            val msg = when (error) {
                                android.speech.SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 에러"
                                android.speech.SpeechRecognizer.ERROR_CLIENT -> "클라이언트 에러"
                                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다."
                                android.speech.SpeechRecognizer.ERROR_NETWORK -> "네트워크 에러"
                                android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과"
                                android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "일치하는 단어가 없습니다. 다시 말씀해 주세요."
                                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성인식 서비스가 바쁩니다."
                                android.speech.SpeechRecognizer.ERROR_SERVER -> "서버 연결에 실패했습니다."
                                android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "입력 시간 초과"
                                else -> "음성인식 에러: $error"
                            }
                            android.widget.Toast.makeText(this@SmartKeyboardService, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        override fun onResults(results: android.os.Bundle?) {
                            val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val transcript = matches[0]
                                if (transcript.isNotEmpty()) {
                                    commitActiveComposition()
                                    currentInputConnection?.commitText(transcript, 1)
                                    android.widget.Toast.makeText(this@SmartKeyboardService, "입력 완료: $transcript", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        override fun onPartialResults(partialResults: android.os.Bundle?) {}
                        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    })
                }

                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@SmartKeyboardService, "음성 인식 초기화 실패: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildKeysLayout() {
        keysContainer.removeAllViews()
        val density = resources.displayMetrics.density

        // Hide or show the bottom spaceRowView depending on whether Geomjigeul (Korean, non-symbols) is active
        val isGeomjigeulActive = activeKoreanLayout == "geomjigeul" && currentLanguage == "ko" && !isSymbolsActive
        if (::spaceRowView.isInitialized) {
            spaceRowView.visibility = if (isGeomjigeulActive) View.GONE else View.VISIBLE
        }

        if (isSymbolsActive) {
            val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            val row2 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
            val row3 = listOf("_", "+", "=", "-", "{", "}", "[", "]", "\\", "|")
            val row4 = listOf(";", ":", "'", "\"", "<", ">", ",", ".", "?", "⌫")
            val row5 = listOf("😃", "🐱", "🚗", "✈", "❤️", "👍", "🔥", "✨", "🎉", "💡")
            keysContainer.addView(createRow(row1))
            keysContainer.addView(createRow(row2))
            keysContainer.addView(createRow(row3))
            keysContainer.addView(createRow(row4))
            keysContainer.addView(createRow(row5))
        } else {
            // Always show numeric row at the top of character layouts
            keysContainer.addView(createRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")))

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
                        keysContainer.addView(createRow(listOf("앱실행", "ㄱ", "ㄴ", "ㄷ", "ㅗ", "ㅏ", "영")))
                        keysContainer.addView(createRow(listOf("클립", "ㄹ", "ㅁ", "ㅂ", "ㅡ", "ㅣ", "123")))
                        keysContainer.addView(createRow(listOf("상용구", "ㅅ", "ㅇ", "ㅈ", "ㅜ", "ㅓ", "⌫")))
                        keysContainer.addView(createRow(listOf("배열", "획추가", ".,!?", "쌍자음", "스페이스", "Enter")))
                    }
                }
            }
        }
    }

    private fun createRow(keys: List<String>): LinearLayout {
        val density = resources.displayMetrics.density
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val paddingDp = if (isLandscape) {
            if (activeKoreanLayout == "geomjigeul") 4f else 6f
        } else {
            14f
        }
        val textMultiplier = if (isLandscape) 0.85f else 1.0f

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (2 * density).toInt(), 0, (2 * density).toInt())
            }
        }

        for (key in keys) {
            val keyButton = Button(this).apply {
                text = if (isShiftActive && currentLanguage == "en" && key.length == 1) key.uppercase() else key
                setTextColor(android.graphics.Color.WHITE)
                val baseTextSize = if (key.length > 1) 12f else 18f
                textSize = baseTextSize * textMultiplier
                isAllCaps = false
                setPadding(0, (paddingDp * density).toInt(), 0, (paddingDp * density).toInt())

                // Style based on function vs character
                val isControl = key == "⇧" || key == "⌫" || key == "획추가" || key == "쌍자음" || 
                                key == "앱실행" || key == "클립" || key == "상용구" || key == "배열" || key == "영" || key == "123" || key == "Enter" || key == ".,!?"
                val bgColor = if (isControl) "#475569" else "#1e293b" // slate-600 vs slate-800
                background = createGradientDrawable(bgColor, 8f)

                var weight = if (isControl) 1.4f else 1.0f
                if (key == "스페이스") {
                    weight = 2.0f
                } else if (activeKoreanLayout == "geomjigeul") {
                    weight = if (key == "스페이스") 2.0f else 1.0f
                }

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
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
                "_", "+", "=", "-", "{", "}", "[", "]", "\\", "|",
                ";", ":", "'", "\"", "<", ">", ",", ".", "?",
                "😃", "🐱", "🚗", "✈", "❤️", "👍", "🔥", "✨", "🎉", "💡" -> {
                    commitActiveComposition()
                    ic.commitText(key, 1)
                    updateSuggestions()
                }
                "앱실행" -> {
                    showAppSelector()
                }
                "클립" -> {
                    showClipboardManager()
                }
                "상용구" -> {
                    showCannedPhrases()
                }
                "배열" -> {
                    cycleLayout()
                    val label = getLayoutLabel()
                    android.widget.Toast.makeText(this, "키보드 배열: $label", android.widget.Toast.LENGTH_SHORT).show()
                }
                "영" -> {
                    commitActiveComposition()
                    currentLanguage = "en"
                    isSymbolsActive = false
                    buildKeysLayout()
                    updateSuggestions()
                }
                "123" -> {
                    commitActiveComposition()
                    isSymbolsActive = true
                    buildKeysLayout()
                }
                "스페이스" -> {
                    commitActiveComposition()
                    ic.commitText(" ", 1)
                    updateSuggestions()
                }
                "Enter" -> {
                    commitActiveComposition()
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    updateSuggestions()
                }
                ".,!?" -> {
                    commitActiveComposition()
                    ic.commitText(".", 1)
                    updateSuggestions()
                }
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
                            "ㅊ" to "ㅉ", "ㅇ" to "ㅎ", "ㅏ" to "ㅑ", "ㅓ" to "ㅕ", "ㅗ" to "ㅛ",
                            "ㅜ" to "ㅠ", "ㅐ" to "ㅒ", "ㅔ" to "ㅖ", "ㅑ" to "ㅏ", "ㅕ" to "ㅓ",
                            "요" to "ㅗ", "ㅠ" to "ㅜ"
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

    private fun showAppSelector() {
        val density = resources.displayMetrics.density
        keysContainer.removeAllViews()

        // 1. Title Bar
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#1e293b"))
        }

        val titleText = android.widget.TextView(this).apply {
            text = "키보드 앱 관리 센터 - 앱 실행"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleText)

        val closeBtn = Button(this).apply {
            text = "닫기 ✕"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            background = createGradientDrawable("#ef4444", 4f)
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (30 * density).toInt())
            setOnClickListener {
                triggerHapticFeedback()
                buildKeysLayout()
            }
        }
        titleBar.addView(closeBtn)
        keysContainer.addView(titleBar)

        // 2. ScrollView for App List
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (160 * density).toInt()
            )
        }

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        // Retrieve real installed launcher apps
        try {
            val pm = packageManager
            val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolvedInfos = pm.queryIntentActivities(mainIntent, 0)
            resolvedInfos.sortBy { it.loadLabel(pm).toString() }

            for (info in resolvedInfos) {
                val appName = info.loadLabel(pm).toString()
                val pkgName = info.activityInfo.packageName

                val itemBtn = Button(this).apply {
                    text = appName
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                    background = createGradientDrawable("#1e293b", 4f)
                    setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                    isAllCaps = false
                    
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (6 * density).toInt())
                    }
                    layoutParams = lp

                    setOnClickListener {
                        triggerHapticFeedback()
                        try {
                            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launchIntent)
                                buildKeysLayout()
                            } else {
                                android.widget.Toast.makeText(this@SmartKeyboardService, "앱을 실행할 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this@SmartKeyboardService, "실행 실패: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                listLayout.addView(itemBtn)
            }
        } catch (e: Exception) {
            val defaults = listOf(
                "네이버" to "com.nhn.android.search",
                "카카오톡" to "com.kakao.talk",
                "YouTube" to "com.google.android.youtube",
                "Chrome" to "com.android.chrome",
                "설정" to "com.android.settings"
            )
            for ((name, pkg) in defaults) {
                val itemBtn = Button(this).apply {
                    text = name
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                    background = createGradientDrawable("#1e293b", 4f)
                    setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                    isAllCaps = false
                    
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (6 * density).toInt())
                    }
                    layoutParams = lp

                    setOnClickListener {
                        triggerHapticFeedback()
                        try {
                            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launchIntent)
                                buildKeysLayout()
                            } else {
                                android.widget.Toast.makeText(this@SmartKeyboardService, "$name 앱이 설치되어 있지 않습니다.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (ex: Exception) {
                            android.widget.Toast.makeText(this@SmartKeyboardService, "실행 실패: ${ex.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                listLayout.addView(itemBtn)
            }
        }

        scrollView.addView(listLayout)
        keysContainer.addView(scrollView)
    }

    private fun showClipboardManager() {
        val density = resources.displayMetrics.density
        keysContainer.removeAllViews()

        // Title Bar
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#1e293b"))
        }

        val titleText = android.widget.TextView(this).apply {
            text = "클립보드 기록"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleText)

        val closeBtn = Button(this).apply {
            text = "닫기 ✕"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            background = createGradientDrawable("#ef4444", 4f)
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (30 * density).toInt())
            setOnClickListener {
                triggerHapticFeedback()
                buildKeysLayout()
            }
        }
        titleBar.addView(closeBtn)
        keysContainer.addView(titleBar)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (160 * density).toInt()
            )
        }

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        // Fetch clipboard history
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clips = mutableListOf<String>()

        if (clipboard != null && clipboard.hasPrimaryClip()) {
            val clipData = clipboard.primaryClip
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val textVal = clipData.getItemAt(i).text?.toString()
                    if (!textVal.isNullOrEmpty()) {
                        clips.add(textVal)
                    }
                }
            }
        }

        if (clips.isEmpty()) {
            clips.add("[복사된 문장 예시] 안녕하세요, 스마트 키보드를 이용해 주셔서 감사합니다!")
            clips.add("https://ai.studio/build")
            clips.add("검지글 자판 사용법을 꼭 익혀 보세요.")
        }

        for (clip in clips) {
            val itemBtn = Button(this).apply {
                text = if (clip.length > 50) clip.substring(0, 47) + "..." else clip
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                background = createGradientDrawable("#1e293b", 4f)
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                isAllCaps = false
                
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * density).toInt())
                }
                layoutParams = lp

                setOnClickListener {
                    triggerHapticFeedback()
                    commitActiveComposition()
                    currentInputConnection?.commitText(clip, 1)
                    buildKeysLayout()
                }
            }
            listLayout.addView(itemBtn)
        }

        scrollView.addView(listLayout)
        keysContainer.addView(scrollView)
    }

    private fun showCannedPhrases() {
        val density = resources.displayMetrics.density
        keysContainer.removeAllViews()

        // Title Bar
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#1e293b"))
        }

        val titleText = android.widget.TextView(this).apply {
            text = "상용구 목록"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleText)

        val closeBtn = Button(this).apply {
            text = "닫기 ✕"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            background = createGradientDrawable("#ef4444", 4f)
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (30 * density).toInt())
            setOnClickListener {
                triggerHapticFeedback()
                buildKeysLayout()
            }
        }
        titleBar.addView(closeBtn)
        keysContainer.addView(titleBar)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (160 * density).toInt()
            )
        }

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        val phrases = listOf(
            "감사합니다. 좋은 하루 보내세요!",
            "지금 회의 중이라 이따가 연락드리겠습니다.",
            "넵 알겠습니다! 바로 확인해 볼게요.",
            "죄송합니다. 오늘 조금 늦을 것 같습니다.",
            "혹시 시간 나실 때 연락 부탁드립니다.",
            "도착하면 미리 말씀해 주세요.",
            "오늘도 화이팅입니다!"
        )

        for (phrase in phrases) {
            val itemBtn = Button(this).apply {
                text = phrase
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                background = createGradientDrawable("#1e293b", 4f)
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                isAllCaps = false
                
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * density).toInt())
                }
                layoutParams = lp

                setOnClickListener {
                    triggerHapticFeedback()
                    commitActiveComposition()
                    currentInputConnection?.commitText(phrase, 1)
                    buildKeysLayout()
                }
            }
            listLayout.addView(itemBtn)
        }

        scrollView.addView(listLayout)
        keysContainer.addView(scrollView)
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
                    "ㅣㅏ" -> "ㅑ"
                    "ㅣㅓ" -> "ㅕ"
                    "ㅣㅗ" -> "ㅛ"
                    "ㅣㅜ" -> "ㅠ"
                    "ㅡㅣ" -> "ㅢ"
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
