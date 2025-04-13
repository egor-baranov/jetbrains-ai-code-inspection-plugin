package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService

sealed class Action {
    data class AddInspection(val inspection: InspectionService.Inspection) : Action()
    data class ApplyInspection(val inspection: InspectionService.Inspection) : Action()
    data class RequestContext(val contextType: String) : Action()
    data class Error(val message: String) : Action()
}