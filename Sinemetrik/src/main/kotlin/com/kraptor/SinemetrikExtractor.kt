package com.kraptor

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SinemetrikExtractor : ExtractorApi() {
    override val name = "Sinemetrik"
    override val mainUrl = "https://sinemetrik.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("kraptor_Sinemetrik","url = $url")
        // Şimdilik standart şablon olarak bırakıyoruz[cite: 13]
    }
}