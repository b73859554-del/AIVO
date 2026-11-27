package io.aivo.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var preferences: android.content.SharedPreferences
    private var currentStep = 0
    private var nameDraft = ""
    private var editingProfile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        if (savedInstanceState != null) {
            currentStep = savedInstanceState.getInt(STATE_STEP, 0)
            nameDraft = savedInstanceState.getString(STATE_NAME_DRAFT).orEmpty()
            editingProfile = savedInstanceState.getBoolean(STATE_EDITING_PROFILE, false)
        } else {
            nameDraft = preferences.getString(KEY_DISPLAY_NAME, "").orEmpty()
        }

        if (preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false) && !editingProfile) {
            showDashboard()
        } else {
            showOnboarding(currentStep)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_STEP, currentStep)
        outState.putString(STATE_NAME_DRAFT, nameDraft)
        outState.putBoolean(STATE_EDITING_PROFILE, editingProfile)
        super.onSaveInstanceState(outState)
    }

    private fun showOnboarding(step: Int) {
        currentStep = step.coerceIn(0, ONBOARDING_STEPS - 1)
        val (_, content) = createScreen()
        addBrandHeader(content)
        addVerticalSpace(content, 36)

        addText(
            content,
            getString(R.string.onboarding_step, currentStep + 1, ONBOARDING_STEPS),
            12f,
            R.color.aivo_primary,
            Typeface.BOLD,
        )
        addVerticalSpace(content, 12)
        addProgressIndicator(content)
        addVerticalSpace(content, 34)

        when (currentStep) {
            0 -> {
                addText(content, getString(R.string.onboarding_welcome_title), 32f, R.color.aivo_ink, Typeface.BOLD)
                addVerticalSpace(content, 12)
                addText(content, getString(R.string.onboarding_welcome_body), 17f, R.color.aivo_muted)
                addVerticalSpace(content, 28)
                addInfoCard(
                    content,
                    getString(R.string.onboarding_intro_card_title),
                    getString(R.string.onboarding_intro_card_body),
                )
                addPrimaryButton(content, getString(R.string.continue_label)) {
                    showOnboarding(1)
                }
                addSecondaryButton(content, getString(R.string.skip_intro)) {
                    showOnboarding(2)
                }
            }

            1 -> {
                addText(content, getString(R.string.onboarding_privacy_title), 32f, R.color.aivo_ink, Typeface.BOLD)
                addVerticalSpace(content, 12)
                addText(content, getString(R.string.onboarding_privacy_body), 17f, R.color.aivo_muted)
                addVerticalSpace(content, 28)
                addInfoCard(
                    content,
                    getString(R.string.onboarding_privacy_card_title),
                    getString(R.string.onboarding_privacy_card_body),
                )
                addPrimaryButton(content, getString(R.string.setup_profile)) {
                    showOnboarding(2)
                }
                addSecondaryButton(content, getString(R.string.back_label)) {
                    showOnboarding(0)
                }
            }

            else -> {
                addText(content, getString(R.string.onboarding_profile_title), 32f, R.color.aivo_ink, Typeface.BOLD)
                addVerticalSpace(content, 12)
                addText(content, getString(R.string.onboarding_profile_body), 17f, R.color.aivo_muted)
                addVerticalSpace(content, 28)
                addText(content, getString(R.string.display_name_label), 14f, R.color.aivo_ink, Typeface.BOLD)
                addVerticalSpace(content, 8)
                addDisplayNameInput(content)
                addVerticalSpace(content, 8)
                addText(content, getString(R.string.display_name_hint), 13f, R.color.aivo_muted)
                addPrimaryButton(content, getString(R.string.open_dashboard)) {
                    saveProfileAndOpenDashboard()
                }
                addSecondaryButton(content, if (editingProfile) getString(R.string.cancel_label) else getString(R.string.back_label)) {
                    if (editingProfile) {
                        hideKeyboard()
                        showDashboard()
                    } else {
                        showOnboarding(1)
                    }
                }
            }
        }
        addFooter(content)
    }

    private fun showDashboard() {
        editingProfile = false
        val (_, content) = createScreen()
        addBrandHeader(content)
        addVerticalSpace(content, 42)

        val displayName = ProfileRules.firstName(preferences.getString(KEY_DISPLAY_NAME, "").orEmpty())
        val greeting = if (displayName == null) {
            getString(R.string.dashboard_welcome)
        } else {
            getString(R.string.dashboard_greeting, displayName)
        }
        addText(content, greeting, 30f, R.color.aivo_ink, Typeface.BOLD)
        addVerticalSpace(content, 8)
        addText(content, getString(R.string.dashboard_subtitle), 16f, R.color.aivo_muted)
        addVerticalSpace(content, 28)

        addInfoCard(
            content,
            getString(R.string.dashboard_workspace_title),
            getString(R.string.dashboard_workspace_body),
        )
        addVerticalSpace(content, 14)
        addStatusCard(
            content,
            getString(R.string.dashboard_profile_title),
            preferences.getString(KEY_DISPLAY_NAME, "")
                .orEmpty()
                .ifBlank { getString(R.string.not_set) },
            getString(R.string.dashboard_profile_body),
        )
        addVerticalSpace(content, 14)
        val endpointConfigured = BackendConfiguration.proxyBaseUrl != null
        addStatusCard(
            content,
            getString(R.string.dashboard_connection_title),
            getString(if (endpointConfigured) R.string.endpoint_configured else R.string.endpoint_not_configured),
            getString(
                if (endpointConfigured) {
                    R.string.dashboard_connection_configured_body
                } else {
                    R.string.dashboard_connection_body
                },
            ),
        )
        addPrimaryButton(content, getString(R.string.open_chat)) {
            startActivity(android.content.Intent(this, ChatActivity::class.java))
        }
        addSecondaryButton(content, getString(R.string.account_title)) {
            startActivity(android.content.Intent(this, AccountActivity::class.java))
        }
        addPrimaryButton(content, getString(R.string.edit_profile)) {
            editingProfile = true
            nameDraft = preferences.getString(KEY_DISPLAY_NAME, "").orEmpty()
            showOnboarding(2)
        }
        addFooter(content)
    }

    private fun createScreen(): Pair<ScrollView, LinearLayout> {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(getColor(R.color.aivo_canvas))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scrollView.addView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scrollView.setOnApplyWindowInsetsListener { view, insets ->
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, systemBars.top, 0, systemBars.bottom)
                insets
            }
        }
        setContentView(scrollView)
        return scrollView to content
    }

    private fun addBrandHeader(parent: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = getString(R.string.brand_mark)
            textSize = 21f
            setTextColor(getColor(R.color.aivo_white))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBackground(getColor(R.color.aivo_primary), 16f)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val name = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        addText(name, getString(R.string.app_name), 18f, R.color.aivo_ink, Typeface.BOLD)
        addText(name, getString(R.string.brand_tagline), 10f, R.color.aivo_muted, Typeface.BOLD)
        header.addView(name)
        parent.addView(header)
    }

    private fun addProgressIndicator(parent: LinearLayout) {
        val progress = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(ONBOARDING_STEPS) { index ->
            val segment = View(this).apply {
                background = roundedBackground(
                    getColor(if (index <= currentStep) R.color.aivo_primary else R.color.aivo_border),
                    4f,
                )
            }
            val params = LinearLayout.LayoutParams(0, dp(5), 1f)
            if (index < ONBOARDING_STEPS - 1) params.marginEnd = dp(8)
            progress.addView(segment, params)
        }
        parent.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)))
    }

    private fun addDisplayNameInput(parent: LinearLayout) {
        val input = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(getColor(R.color.aivo_ink))
            setHintTextColor(getColor(R.color.aivo_hint))
            hint = getString(R.string.display_name_placeholder)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            filters = arrayOf(InputFilter.LengthFilter(ProfileRules.MAX_DISPLAY_NAME_LENGTH))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(
                getColor(R.color.aivo_white),
                14f,
                getColor(R.color.aivo_border),
            )
            contentDescription = getString(R.string.display_name_label)
            setText(nameDraft)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    nameDraft = text?.toString().orEmpty()
                }
            })
        }
        parent.addView(
            input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addInfoCard(parent: LinearLayout, title: String, body: String) {
        val card = createCard(parent)
        addText(card, title, 17f, R.color.aivo_ink, Typeface.BOLD)
        addVerticalSpace(card, 8)
        addText(card, body, 15f, R.color.aivo_muted)
        parent.addView(
            card,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addStatusCard(parent: LinearLayout, title: String, value: String, body: String) {
        val card = createCard(parent)
        addText(card, title.uppercase(), 11f, R.color.aivo_primary, Typeface.BOLD)
        addVerticalSpace(card, 9)
        addText(card, value, 18f, R.color.aivo_ink, Typeface.BOLD)
        addVerticalSpace(card, 5)
        addText(card, body, 14f, R.color.aivo_muted)
        parent.addView(
            card,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun createCard(parent: LinearLayout): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedBackground(
                getColor(R.color.aivo_white),
                20f,
                getColor(R.color.aivo_border),
            )
        }

    private fun addPrimaryButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.aivo_white))
            background = roundedBackground(getColor(R.color.aivo_primary), 16f)
            backgroundTintList = null
            minHeight = dp(54)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { action() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(28)
        parent.addView(button, params)
    }

    private fun addSecondaryButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(getColor(R.color.aivo_primary))
            background = roundedBackground(getColor(R.color.aivo_canvas), 14f)
            backgroundTintList = null
            minHeight = dp(48)
            setOnClickListener { action() }
        }
        parent.addView(
            button,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        sizeSp: Float,
        colorRes: Int,
        style: Int = Typeface.NORMAL,
    ): TextView {
        val textView = TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(getColor(colorRes))
            typeface = Typeface.create(Typeface.DEFAULT, style)
            if (sizeSp >= 16f) setLineSpacing(dp(2).toFloat(), 1f)
        }
        parent.addView(
            textView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return textView
    }

    private fun addFooter(parent: LinearLayout) {
        addVerticalSpace(parent, 26)
        addText(parent, getString(R.string.footer_note), 12f, R.color.aivo_muted)
    }

    private fun addVerticalSpace(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun roundedBackground(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun saveProfileAndOpenDashboard() {
        hideKeyboard()
        val normalizedName = ProfileRules.normalizeDisplayName(nameDraft)
        preferences.edit()
            .putString(KEY_DISPLAY_NAME, normalizedName)
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
        nameDraft = normalizedName
        showDashboard()
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.windowToken?.let { token ->
            inputMethodManager.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFERENCES_NAME = "aivo_profile"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val STATE_STEP = "onboarding_step"
        private const val STATE_NAME_DRAFT = "display_name_draft"
        private const val STATE_EDITING_PROFILE = "editing_profile"
        private const val ONBOARDING_STEPS = 3
    }
}
