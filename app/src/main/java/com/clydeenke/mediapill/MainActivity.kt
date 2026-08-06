package com.clydeenke.mediapill

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clydeenke.mediapill.config.Config
import com.clydeenke.mediapill.config.ConfigService
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * MediaPill 设置界面 — miuix-kmp 风格。
 *
 * 可调节参数：
 * - 总开关
 * - 药丸 Y 轴偏移（-50 ~ +50 dp）
 * - 药丸宽度（200 ~ 320 dp）
 * - 背景透明度（60% ~ 95%）
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val dark = isSystemInDarkTheme()
            val prefs = ConfigService.get()

            // 读取当前配置
            var masterSwitch by remember {
                mutableStateOf(
                    prefs?.getBoolean(Config.KEY_MASTER_SWITCH, Config.MASTER_SWITCH_DEFAULT)
                        ?: Config.MASTER_SWITCH_DEFAULT
                )
            }

            var yOffset by remember {
                mutableIntStateOf(
                    prefs?.getInt(Config.KEY_PILL_Y_OFFSET_DP, Config.PILL_Y_OFFSET_DP_DEFAULT)
                        ?: Config.PILL_Y_OFFSET_DP_DEFAULT
                )
            }

            var pillWidth by remember {
                mutableIntStateOf(
                    prefs?.getInt(Config.KEY_PILL_MAX_WIDTH_DP, Config.PILL_MAX_WIDTH_DP_DEFAULT)
                        ?: Config.PILL_MAX_WIDTH_DP_DEFAULT
                )
            }

            var alphaPercent by remember {
                mutableIntStateOf(
                    prefs?.getInt(Config.KEY_PILL_ALPHA_PERCENT, Config.PILL_ALPHA_PERCENT_DEFAULT)
                        ?: Config.PILL_ALPHA_PERCENT_DEFAULT
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
                        // ═══════════════════════════════════════════════════════
                        //  总开关
                        // ═══════════════════════════════════════════════════════
                        SmallTitle(text = "功能开关")
                        SwitchPreference(
                            title = "启用 MediaPill",
                            summary = if (masterSwitch) "已开启，锁屏时将显示媒体药丸" else "已关闭，使用系统默认媒体控件",
                            checked = masterSwitch,
                            onCheckedChange = { v ->
                                masterSwitch = v
                                prefs?.edit()?.putBoolean(Config.KEY_MASTER_SWITCH, v)?.apply()
                            }
                        )

                        // ═══════════════════════════════════════════════════════
                        //  位置设置
                        // ═══════════════════════════════════════════════════════
                        SmallTitle(text = "位置设置")
                        SliderPreference(
                            title = "垂直位置偏移",
                            summary = "调整药丸在锁屏的上下位置（${yOffset}dp）",
                            value = yOffset.toFloat(),
                            onValueChange = { yOffset = it.toInt() },
                            onValueChangeFinished = {
                                prefs?.edit()?.putInt(Config.KEY_PILL_Y_OFFSET_DP, yOffset)?.apply()
                            },
                            valueRange = -50f..50f,
                            steps = 100
                        )

                        // ═══════════════════════════════════════════════════════
                        //  外观设置
                        // ═══════════════════════════════════════════════════════
                        SmallTitle(text = "外观设置")
                        SliderPreference(
                            title = "药丸宽度",
                            summary = "调整药丸的宽度（${pillWidth}dp）",
                            value = pillWidth.toFloat(),
                            onValueChange = { pillWidth = it.toInt() },
                            onValueChangeFinished = {
                                prefs?.edit()?.putInt(Config.KEY_PILL_MAX_WIDTH_DP, pillWidth)?.apply()
                            },
                            valueRange = 200f..320f,
                            steps = 120
                        )

                        SliderPreference(
                            title = "背景透明度",
                            summary = "调整玻璃背景的透明度（${alphaPercent}%）",
                            value = alphaPercent.toFloat(),
                            onValueChange = { alphaPercent = it.toInt() },
                            onValueChangeFinished = {
                                prefs?.edit()?.putInt(Config.KEY_PILL_ALPHA_PERCENT, alphaPercent)?.apply()
                            },
                            valueRange = 60f..95f,
                            steps = 35
                        )

                        // ═══════════════════════════════════════════════════════
                        //  关于
                        // ═══════════════════════════════════════════════════════
                        SmallTitle(text = "关于")
                        top.yukonga.miuix.kmp.basic.Text(
                            text = "MediaPill v1.0\n为 Android 锁屏带来精致的媒体控制体验",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
