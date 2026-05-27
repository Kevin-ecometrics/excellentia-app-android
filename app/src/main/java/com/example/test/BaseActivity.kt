package com.example.test

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.example.test.data.local.SecurePreferences

abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = SecurePreferences(newBase).getLanguage()
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }
}
