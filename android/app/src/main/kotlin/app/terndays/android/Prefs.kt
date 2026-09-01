package app.terndays.android

import android.content.Context

object Prefs {
    private fun sp(context: Context) = context.getSharedPreferences("terndays", Context.MODE_PRIVATE)

    fun onboardingDone(context: Context): Boolean = sp(context).getBoolean("onboarding_done", false)

    fun setOnboardingDone(context: Context) {
        sp(context).edit().putBoolean("onboarding_done", true).apply()
    }
}
