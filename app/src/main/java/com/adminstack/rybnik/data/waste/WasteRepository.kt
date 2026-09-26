package com.adminstack.rybnik.data.waste

import android.content.Context
import com.adminstack.rybnik.core.net.CachedRemoteSource
import com.adminstack.rybnik.core.net.RemoteConfig
import com.adminstack.rybnik.core.net.sharedJson

class WasteRepository(context: Context) :
    CachedRemoteSource<WastePayload>(context, RemoteConfig.dataUrl("waste.json"), "waste.json") {

    override fun parse(body: String): WastePayload = sharedJson.decodeFromString(body)

    fun schedule(): WasteSchedule = WasteSchedule(data.value?.rejony ?: emptyList())
}
