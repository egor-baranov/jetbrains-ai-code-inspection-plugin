package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.Action
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.project.Project
import java.util.*

class AddInspectionHandler(
    private val project: Project
) {

    private val gson = Gson()

    fun handleAddInspection(
        arguments: String,
        files: List<InspectionService.CodeFile>,
        inspectionOffset: Int
    ): Action? {
        if (InspectionService.getInstance(project).inspectionFiles.size >= inspectionOffset) {
            InspectionService.getInstance(project).cancelInspection()
            return null
        }

        val args = gson.fromJson(arguments, AddInspectionArgs::class.java)
        val inspection = InspectionService.Inspection(
            id = UUID.randomUUID().toString(),
            description = args.description,
            fixPrompt = args.fixPrompt
        )

        InspectionService.getInstance(project).putInspection(inspection, files)
        return Action.AddInspection(inspection)
    }

    private data class AddInspectionArgs(
        val description: String,
        @SerializedName("fix_prompt") val fixPrompt: String
    )
}