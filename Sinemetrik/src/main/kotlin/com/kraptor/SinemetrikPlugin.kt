package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SinemetrikPlugin: Plugin() {
    override fun load(context: Context) {
        // Ana eklentiyi ve video yakalayıcıyı kaydediyoruz[cite: 14]
        registerMainAPI(Sinemetrik())
        registerExtractorAPI(SinemetrikExtractor())
    }
}