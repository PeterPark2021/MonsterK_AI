package com.smart.keyboard.ime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnEnable: Button
    private lateinit var btnSelect: Button
    private lateinit var layoutSelectorGroup: RadioGroup
    private lateinit var vibrationSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup SharedPreferences
        val prefs = getSharedPreferences("kboard_settings", Context.MODE_PRIVATE)

        // Base scroll view container
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0f172a")) // Slate-900 canvas
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 64, 48, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Header Title
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 32)
            }
        }

        val logoBox = TextView(this).apply {
            text = "K"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val density = resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(),
                (44 * density).toInt()
            ).apply {
                setMargins(0, 0, 16, 0)
            }
            background = createGradientDrawable("#4f46e5", 12f) // indigo-600
        }
        titleLayout.addView(logoBox)

        val titleText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val appName = TextView(this).apply {
            text = "K-Board Pro Native"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val appVersion = TextView(this).apply {
            text = "v2.5.2 Pro · 업데이트 완료"
            setTextColor(Color.parseColor("#818cf8")) // Indigo-400
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        titleText.addView(appName)
        titleText.addView(appVersion)
        titleLayout.addView(titleText)
        mainLayout.addView(titleLayout)

        // Info Banner Card
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = createGradientDrawable("#1e293b", 16f) // Slate-800
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 48)
            }
        }
        val bannerTitle = TextView(this).apply {
            text = "✨ 실시간 오토마타 엔진 연동 완료"
            setTextColor(Color.parseColor("#38bdf8")) // Sky-400
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        val bannerDesc = TextView(this).apply {
            text = "안드로이드 Native 입력기와 한글 오토마타 엔진의 구성을 웹 시뮬레이터의 고급 UI/UX 기능 및 자판 배열과 완벽히 동일하게 연동하고, 한영 전환과 자판 레이아웃 오작동 문제를 근본적으로 해결한 완성된 Native 버전으로 재구현 및 복원되었습니다. 기존 앱 삭제 없이 바로 업데이트 가능합니다."
            setTextColor(Color.parseColor("#cbd5e1")) // Slate-300
            textSize = 11f
            setLineSpacing(0f, 1.3f)
        }
        infoCard.addView(bannerTitle)
        infoCard.addView(bannerDesc)
        mainLayout.addView(infoCard)

        // Step 1: Enable Keyboard
        val step1Header = TextView(this).apply {
            text = "1단계: 키보드 활성화 (Enable)"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(step1Header)

        btnEnable = Button(this).apply {
            text = "설정에서 K-Board Pro 활성화"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = createGradientDrawable("#334155", 10f) // Slate-700
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 48)
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
        mainLayout.addView(btnEnable)

        // Step 2: Select Default Keyboard
        val step2Header = TextView(this).apply {
            text = "2단계: 기본 입력법 지정 (Select)"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(step2Header)

        btnSelect = Button(this).apply {
            text = "K-Board Pro를 기본 입력기로 선택"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = createGradientDrawable("#334155", 10f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 48)
            }
            setOnClickListener {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
        mainLayout.addView(btnSelect)

        // Step 3: Layout Configuration
        val step3Header = TextView(this).apply {
            text = "3단계: 자판 레이아웃 설정 (Layout)"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(step3Header)

        val layoutSelectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = createGradientDrawable("#1e293b", 16f) // Slate-800
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 48)
            }
        }

        val layoutTitleDesc = TextView(this).apply {
            text = "자판 배열 선택"
            setTextColor(Color.parseColor("#94a3b8")) // Slate-400
            textSize = 11f
            setPadding(0, 0, 0, 16)
        }
        layoutSelectorCard.addView(layoutTitleDesc)

        val radioQwerty = RadioButton(this).apply {
            text = "두벌식 (QWERTY) 레이아웃"
            setTextColor(Color.WHITE)
            textSize = 12f
            id = View.generateViewId()
        }
        val radioCheonjiin = RadioButton(this).apply {
            text = "천지인 (Cheonjiin) 레이아웃"
            setTextColor(Color.WHITE)
            textSize = 12f
            id = View.generateViewId()
        }
        val radioNaratgul = RadioButton(this).apply {
            text = "나랏글 (Naratgul) 레이아웃"
            setTextColor(Color.WHITE)
            textSize = 12f
            id = View.generateViewId()
        }
        val radioGeomjigeul = RadioButton(this).apply {
            text = "검지글 (Geomjigeul) 레이아웃"
            setTextColor(Color.WHITE)
            textSize = 12f
            id = View.generateViewId()
        }

        layoutSelectorGroup = RadioGroup(this).apply {
            addView(radioQwerty)
            addView(radioCheonjiin)
            addView(radioNaratgul)
            addView(radioGeomjigeul)
        }

        // Load active layout from SharedPreferences
        val currentLayout = prefs.getString("activeKoreanLayout", "cheonjiin") ?: "cheonjiin"
        when (currentLayout) {
            "qwerty" -> radioQwerty.isChecked = true
            "cheonjiin" -> radioCheonjiin.isChecked = true
            "naratgul" -> radioNaratgul.isChecked = true
            "geomjigeul" -> radioGeomjigeul.isChecked = true
        }

        layoutSelectorGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedLayout = when (checkedId) {
                radioQwerty.id -> "qwerty"
                radioCheonjiin.id -> "cheonjiin"
                radioNaratgul.id -> "naratgul"
                radioGeomjigeul.id -> "geomjigeul"
                else -> "cheonjiin"
            }
            prefs.edit().putString("activeKoreanLayout", selectedLayout).commit()
            Toast.makeText(this, "자판 레이아웃이 ${getLayoutLabel(selectedLayout)}로 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }

        layoutSelectorCard.addView(layoutSelectorGroup)
        mainLayout.addView(layoutSelectorCard)

        // Step 4: Preferences Toggle
        val step4Header = TextView(this).apply {
            text = "4단계: 키보드 부가 환경 설정"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(step4Header)

        val configCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 32, 32, 32)
            background = createGradientDrawable("#1e293b", 16f) // Slate-800
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 48)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        val configTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val configTitle = TextView(this).apply {
            text = "터치 시 햅틱 진동"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        val configDesc = TextView(this).apply {
            text = "키 버튼을 입력할 때 부드러운 햅틱 반응 제공"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 10f
        }
        configTextLayout.addView(configTitle)
        configTextLayout.addView(configDesc)
        configCard.addView(configTextLayout)

        vibrationSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("vibrateOnPress", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("vibrateOnPress", isChecked).commit()
            }
        }
        configCard.addView(vibrationSwitch)
        mainLayout.addView(configCard)

        // Typing Sandbox Section
        val step5Header = TextView(this).apply {
            text = "키보드 조합 테스트 영역"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(step5Header)

        val testEditText = EditText(this).apply {
            hint = "여기를 탭하여 새로 업데이트된 키보드 배열을 조합하고 테스트해 보세요!"
            setHintTextColor(Color.parseColor("#64748b"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 24, 24, 24)
            background = createGradientDrawable("#020617", 10f) // Slate-950
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        mainLayout.addView(testEditText)

        // Request Microphone (RECORD_AUDIO) permission for Voice Input
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
        }

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun getLayoutLabel(layout: String): String {
        return when (layout) {
            "qwerty" -> "두벌식 (QWERTY)"
            "cheonjiin" -> "천지인"
            "naratgul" -> "나랏글"
            "geomjigeul" -> "검지글"
            else -> "천지인"
        }
    }

    private fun createGradientDrawable(colorHex: String, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(Color.parseColor(colorHex))
        val density = resources.displayMetrics.density
        drawable.cornerRadius = radiusDp * density
        return drawable
    }
}
