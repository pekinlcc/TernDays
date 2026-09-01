package app.terndays.android

import android.content.Context

object Prefs {
    private fun sp(context: Context) = context.getSharedPreferences("terndays", Context.MODE_PRIVATE)

    fun onboardingDone(context: Context): Boolean = sp(context).getBoolean("onboarding_done", false)

    fun setOnboardingDone(context: Context) {
        sp(context).edit().putBoolean("onboarding_done", true).apply()
    }

    /** 历史打卡最近一次是用哪个版本的城市库解析的（用于升级后重解析）。 */
    fun datasetVersionResolved(context: Context): Int = sp(context).getInt("dataset_version_resolved", 1)

    fun setDatasetVersionResolved(context: Context, version: Int) {
        sp(context).edit().putInt("dataset_version_resolved", version).apply()
    }
}
