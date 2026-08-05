package com.clydeenke.mediapill

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.clydeenke.mediapill.config.Config
import com.clydeenke.mediapill.config.ConfigService
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val prefs = ConfigService.get()
            var masterSwitch by remember {
                mutableStateOf(
                    prefs?.getBoolean(Config.MASTER_SWITCH, Config.MASTER_SWITCH_DEFAULT)
                        ?: Config.MASTER_SWITCH_DEFAULT
                )
            }

            MiuixTheme(
                colors = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Scaffold(
                    topBar = { TopAppBar(title = "MediaPill") }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(padding)
                    ) {
                        SmallTitle(text = getString(R.string.cat_general))
                        SwitchPreference(
                            title = getString(R.string.master_switch_title),
                            summary = getString(R.string.master_switch_summary),
                            checked = masterSwitch,
                            onCheckedChange = { v ->
                                masterSwitch = v
                                ConfigService.get()?.edit()
                                    ?.putBoolean(Config.MASTER_SWITCH, v)?.apply()
                            }
                        )
                    }
                }
            }
        }
    }
}
