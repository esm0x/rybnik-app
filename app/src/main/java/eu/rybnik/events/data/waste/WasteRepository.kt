package eu.rybnik.events.data.waste

import android.content.Context
import eu.rybnik.events.core.net.CachedRemoteSource
import eu.rybnik.events.core.net.RemoteConfig
import eu.rybnik.events.core.net.sharedJson

class WasteRepository(context: Context) :
    CachedRemoteSource<WastePayload>(context, RemoteConfig.dataUrl("waste.json"), "waste.json") {

    override fun parse(body: String): WastePayload = sharedJson.decodeFromString(body)

    fun schedule(): WasteSchedule = WasteSchedule(data.value?.rejony ?: emptyList())
}
