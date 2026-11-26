package cn.yingxia.player

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import java.util.Locale

enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ZH("zh"),
    EN("en"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/** Lightweight on-device locale override with zero extra dependencies. */
object AppLocale {
    private const val PREFS = "yingxia_locale"
    private const val KEY = "language"

    fun current(context: Context): AppLanguage {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")
        return AppLanguage.fromTag(raw)
    }

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
    }

    fun wrap(base: Context): Context {
        val locale = when (current(base)) {
            AppLanguage.SYSTEM -> return base
            AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun str(context: Context, @StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) wrap(context).getString(id) else wrap(context).getString(id, *args)

    fun stringArray(context: Context, @ArrayRes id: Int): Array<String> =
        wrap(context).resources.getStringArray(id)
}
