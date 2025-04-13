package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.Action
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.project.Project

class ApplyInspectionHandler(private val project: Project) {

    private val gson = Gson()

    fun handleApplyInspection(
        arguments: String,
        files: List<InspectionService.CodeFile>
    ): Action {
        val args = gson.fromJson(arguments, ApplyInspectionArgs::class.java)
        println("Apply inspection: $args, ${files.size}")
        val inspection = InspectionService.getInstance(project).getInspectionById(args.inspection_id)
            ?: return Action.Error("Inspection not found: ${args.inspection_id}")
        InspectionService.getInstance(project).addFilesToInspection(inspection, files)
        return Action.ApplyInspection(inspection)
    }

    private data class ApplyInspectionArgs(
        @SerializedName("inspection_id") val inspection_id: String
    )
}