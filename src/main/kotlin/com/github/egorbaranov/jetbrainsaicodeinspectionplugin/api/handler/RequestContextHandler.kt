package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.Action
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

class RequestContextHandler {

    private val gson = Gson()

    fun handleRequestContext(arguments: String): Action {
        val args = gson.fromJson(arguments, RequestContextArgs::class.java)
        return Action.RequestContext(contextType = args.contextType)
    }

    private data class RequestContextArgs(
        @SerializedName("context_type") val contextType: String
    )
}